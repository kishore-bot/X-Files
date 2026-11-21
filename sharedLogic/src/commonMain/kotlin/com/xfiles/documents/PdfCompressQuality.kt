package com.xfiles.documents

/**
 * How hard Compress works on images. [Lossless] only restructures the file.
 * [Balanced] brings large images to about 150 dpi at JPEG quality 75, and [Strong]
 * to about 110 dpi at quality 60. Images that wouldn't shrink are left as they are.
 */
enum class PdfCompressQuality(val label: String, val detail: String) {
    Lossless("Lossless", "Keeps every image byte-for-byte. Removes duplicate data only."),
    Balanced("Balanced", "Large photos at about 150 dpi. Good for sharing."),
    Strong("Strong", "Large photos at about 110 dpi. Smallest file, visible softening."),
}
