package com.xfiles.documentengine

import com.xfiles.engine.ffi.NativeCollection
import com.xfiles.engine.ffi.NativeCollectionMatch
import com.xfiles.engine.ffi.NativeSimilarKind
import com.xfiles.engine.ffi.NativeTidyFile
import com.xfiles.engine.ffi.NativeTidyListener
import com.xfiles.engine.ffi.NativeTidySource
import com.xfiles.engine.ffi.ReadException
import com.xfiles.engine.ffi.tidyAnalyzeText
import com.xfiles.engine.ffi.tidyClassifyName
import com.xfiles.engine.ffi.tidyFindIdentical
import com.xfiles.engine.ffi.tidyGroupSimilar
import com.xfiles.engine.ffi.tidyImageFingerprint
import com.xfiles.engine.ffi.tidySensitiveScan
import com.xfiles.tidy.CollectionKind
import com.xfiles.tidy.CollectionMatch
import com.xfiles.tidy.CollectionReason
import com.xfiles.tidy.HashInput
import com.xfiles.tidy.HashSource
import com.xfiles.tidy.IdenticalHashes
import com.xfiles.tidy.SensitiveFindings
import com.xfiles.tidy.TextFacts
import com.xfiles.tidy.TidyEngine
import kotlinx.coroutines.CancellationException

/** [TidyEngine] over the UniFFI bindings to `document-ffi`'s tidy module. Calls block; run them on an IO lane. */
class RustTidyEngine : TidyEngine {
    override fun findIdentical(files: List<HashInput>, source: HashSource): IdenticalHashes {
        val listener = object : NativeTidyListener {
            override fun open(index: UInt): NativeTidySource? = try {
                source.open(index.toInt())?.let { NativeTidySource(it, null) }
            } catch (_: Exception) {
                null // Must not throw across the FFI boundary: an unreadable file is left out.
            }

            override fun onProgress(done: UInt, total: UInt) = source.onProgress(done.toInt(), total.toInt())

            override fun isCancelled(): Boolean = source.isCancelled()
        }
        val native = files.map { NativeTidyFile(it.size.toULong(), it.partial?.toULong(), it.full?.toULong()) }
        val result = try {
            tidyFindIdentical(native, listener)
        } catch (_: ReadException.Cancelled) {
            throw CancellationException("Tidy scan cancelled")
        }
        return IdenticalHashes(
            groups = result.groups.map { group -> group.map { it.toInt() } },
            partial = result.partialHashes.map { it?.toLong() },
            full = result.fullHashes.map { it?.toLong() },
        )
    }

    override fun analyzeText(name: String, text: String): TextFacts {
        val facts = tidyAnalyzeText(name, text)
        return TextFacts(facts.fingerprint?.toLong(), facts.collection?.toDomain())
    }

    override fun classifyName(name: String): CollectionMatch? = tidyClassifyName(name)?.toDomain()

    override fun imageFingerprint(width: Int, height: Int, gray: ByteArray): Long? =
        tidyImageFingerprint(width.toUInt(), height.toUInt(), gray)?.toLong()

    override fun groupSimilar(fingerprints: List<Long>, images: Boolean): List<List<Int>> =
        tidyGroupSimilar(fingerprints.map { it.toULong() }, if (images) NativeSimilarKind.IMAGES else NativeSimilarKind.DOCUMENTS)
            .map { group -> group.map { it.toInt() } }

    override fun sensitive(text: String): SensitiveFindings {
        val found = tidySensitiveScan(text)
        return SensitiveFindings(found.paymentCard, found.nationalId, found.bankAccount)
    }

    private fun NativeCollectionMatch.toDomain() = CollectionMatch(
        kind = when (collection) {
            NativeCollection.RECEIPTS -> CollectionKind.Receipts
            NativeCollection.TRAVEL -> CollectionKind.Travel
            NativeCollection.BANK_STATEMENTS -> CollectionKind.BankStatements
            NativeCollection.CERTIFICATES -> CollectionKind.Certificates
            NativeCollection.PAY_AND_TAX -> CollectionKind.PayAndTax
            NativeCollection.MEDICAL -> CollectionKind.Medical
            NativeCollection.RESUMES -> CollectionKind.Resumes
        },
        reason = CollectionReason(terms, nameTerms, amounts.toInt(), dates.toInt()),
    )
}
