package com.xfiles.documents

data class DocumentAnalysis(
    val keySentences: List<String>,
    val keywords: List<String>,
    val language: String?,
    val wordCount: UInt,
    val readingMinutes: UInt,
)
