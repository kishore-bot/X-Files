package com.xfiles.documentengine

import com.xfiles.engine.ffi.pdfSignaturesFd

/** A digital signature as the PDF declares it. The cryptography is not checked. */
data class PdfSignatureInfo(
    val field: String,
    val signer: String?,
    val signedAt: String?,
    val reason: String?,
    val location: String?,
    val contact: String?,
    val format: String?,
    val byteRangeOk: Boolean,
    /** No bytes were added after this signature. */
    val coversWholeFile: Boolean,
)

/** The signatures in the PDF open at [fd]; takes ownership of [fd]. */
fun readPdfSignatures(fd: Int): List<PdfSignatureInfo> = pdfSignaturesFd(fd).map {
    PdfSignatureInfo(
        field = it.field,
        signer = it.signer,
        signedAt = it.signedAt,
        reason = it.reason,
        location = it.location,
        contact = it.contact,
        format = it.format,
        byteRangeOk = it.byteRangeOk,
        coversWholeFile = it.coversWholeFile,
    )
}
