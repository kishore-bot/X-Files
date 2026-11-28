package com.xfiles.feature.editor

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Encodings the editor reads and writes. The `label` is what the user sees. */
enum class TextEncoding(val label: String) {
    Utf8("UTF-8"),
    Utf8Bom("UTF-8 with BOM"),
    Utf16Le("UTF-16 LE"),
    Utf16Be("UTF-16 BE"),
    Windows1252("Windows-1252"),
    Latin1("ISO-8859-1"),
}

/** Line endings. The editor works on LF internally and writes [sequence] on save. */
enum class LineEnding(val label: String, val sequence: String) {
    Lf("LF", "\n"),
    Crlf("CRLF", "\r\n"),
    Cr("CR", "\r"),
}

/**
 * A decoded file. [text] uses LF only. [mixedLineEndings] means the file uses more than one kind, so
 * saving would silently change some of them; the editor keeps such a file read-only.
 */
data class DecodedText(
    val text: String,
    val encoding: TextEncoding,
    val lineEnding: LineEnding,
    val mixedLineEndings: Boolean,
)

/** Raised when the chosen encoding cannot store every character of the text. */
class TextEncodingException(message: String) : Exception(message)

/**
 * Byte-level text handling for the editor. Detection order: byte-order mark, strict UTF-8, UTF-16
 * without a BOM (from the zero-byte pattern of ASCII-heavy text), then Windows-1252. Bytes that
 * Windows-1252 leaves undefined (0x81, 0x8D, 0x8F, 0x90, 0x9D) select ISO-8859-1 so the round trip is
 * lossless. Pure functions: safe on any dispatcher, no Android types.
 */
object TextCodec {
    /** Largest file the editor opens. Larger files are read-only elsewhere. */
    const val EDIT_CAP_BYTES = 256 * 1024

    private val UNDEFINED_CP1252 = setOf(0x81, 0x8D, 0x8F, 0x90, 0x9D).map { it.toByte() }.toSet()

    fun detectEncoding(bytes: ByteArray): TextEncoding {
        if (bytes.startsWith(0xEF, 0xBB, 0xBF)) return TextEncoding.Utf8Bom
        if (bytes.startsWith(0xFF, 0xFE)) return TextEncoding.Utf16Le
        if (bytes.startsWith(0xFE, 0xFF)) return TextEncoding.Utf16Be
        if (isValidUtf8(bytes)) return TextEncoding.Utf8
        utf16WithoutBom(bytes)?.let { return it }
        return if (bytes.any { it in UNDEFINED_CP1252 }) TextEncoding.Latin1 else TextEncoding.Windows1252
    }

    fun decode(bytes: ByteArray): DecodedText {
        val encoding = detectEncoding(bytes)
        val body = when (encoding) {
            TextEncoding.Utf8Bom -> bytes.copyOfRange(3, bytes.size)
            TextEncoding.Utf16Le, TextEncoding.Utf16Be ->
                if (bytes.startsWith(0xFF, 0xFE) || bytes.startsWith(0xFE, 0xFF)) bytes.copyOfRange(2, bytes.size) else bytes
            else -> bytes
        }
        val raw = String(body, charset(encoding))
        val ends = countLineEndings(raw)
        val kinds = listOf(ends.crlf, ends.lf, ends.cr).count { it > 0 }
        val dominant = when (ends.max()) {
            ends.crlf -> LineEnding.Crlf
            ends.cr -> LineEnding.Cr
            else -> LineEnding.Lf
        }
        val normalized = if (kinds == 0) raw else raw.replace("\r\n", "\n").replace('\r', '\n')
        return DecodedText(normalized, encoding, dominant, mixedLineEndings = kinds > 1)
    }

    /**
     * Bytes for [text] as [encoding], with [lineEnding] written for every LF. A BOM is written for
     * the BOM encodings and both UTF-16 forms. Throws [TextEncodingException] when a character does
     * not fit the encoding, so nothing is silently replaced.
     */
    fun encode(text: String, encoding: TextEncoding, lineEnding: LineEnding): ByteArray {
        val converted = if (lineEnding == LineEnding.Lf) text else text.replace("\n", lineEnding.sequence)
        val charset = charset(encoding)
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val body = try {
            val buffer = encoder.encode(CharBuffer.wrap(converted))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (error: CharacterCodingException) {
            throw TextEncodingException("Some characters cannot be saved as ${encoding.label}. Choose UTF-8 to keep them.")
        }
        val prefix = when (encoding) {
            TextEncoding.Utf8Bom -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            TextEncoding.Utf16Le -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            TextEncoding.Utf16Be -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> ByteArray(0)
        }
        return prefix + body
    }

    /** Whether [bytes] is well-formed UTF-8 (strict: no replacement characters). */
    fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (error: CharacterCodingException) {
        false
    }

    private fun utf16WithoutBom(bytes: ByteArray): TextEncoding? {
        if (bytes.size < 4) return null
        var zerosAtEven = 0
        var zerosAtOdd = 0
        val pairs = bytes.size / 2
        for (i in 0 until pairs) {
            if (bytes[2 * i] == 0.toByte()) zerosAtEven++
            if (bytes[2 * i + 1] == 0.toByte()) zerosAtOdd++
        }
        // ASCII-heavy UTF-16 has a zero high byte in every unit: even offsets for BE, odd for LE.
        return when {
            zerosAtOdd * 10 >= pairs * 4 && zerosAtEven * 10 < pairs -> TextEncoding.Utf16Le
            zerosAtEven * 10 >= pairs * 4 && zerosAtOdd * 10 < pairs -> TextEncoding.Utf16Be
            else -> null
        }
    }

    private class LineEndingCounts(val crlf: Int, val lf: Int, val cr: Int) {
        fun max(): Int = maxOf(crlf, lf, cr)
    }

    private fun countLineEndings(text: String): LineEndingCounts {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') {
                    crlf++
                    i++
                } else {
                    cr++
                }
                '\n' -> lf++
            }
            i++
        }
        return LineEndingCounts(crlf, lf, cr)
    }

    private fun charset(encoding: TextEncoding): Charset = when (encoding) {
        TextEncoding.Utf8, TextEncoding.Utf8Bom -> Charsets.UTF_8
        TextEncoding.Utf16Le -> Charsets.UTF_16LE
        TextEncoding.Utf16Be -> Charsets.UTF_16BE
        TextEncoding.Windows1252 -> Charset.forName("windows-1252")
        TextEncoding.Latin1 -> Charsets.ISO_8859_1
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
