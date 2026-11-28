package com.xfiles.documents

/**
 * How a protected PDF is protected again after a tool runs on it.
 * [SameAsOriginal] keeps the file's own algorithm, limits and passwords (RC4 files stay RC4).
 * [Aes256] writes AES-256 with the file's limits.
 */
enum class PdfProtectionChoice(val label: String) {
    SameAsOriginal("Same as original"),
    Aes256("AES-256"),
}

/** What the engine did with the protection of a result. */
enum class PdfProtectionOutcome {
    /** The source had no encryption, so the result has none. */
    NotProtected,

    /** Protected again with the file's own algorithm and passwords. */
    Kept,

    /** Protected with AES-256, as chosen. */
    Upgraded,

    /** The owner password wasn't given, so the limits came back under a new random owner password. */
    KeptNewOwner,

    /** Opened with the owner password, which the result now opens with too. */
    OwnerPasswordOpens,

    /** No user password and no limits: nothing to keep. */
    SavedWithoutProtection,
}

/**
 * The one line that says what happened to the protection. Null when there is
 * nothing to say (the source wasn't protected). Restrictions are named so the
 * user knows whether the file still refuses printing or changes.
 */
fun PdfProtectionOutcome.statusLine(restrictionsKept: Boolean): String? = when (this) {
    PdfProtectionOutcome.NotProtected -> null
    PdfProtectionOutcome.Kept ->
        if (restrictionsKept) "Restrictions kept" else "Password protection kept"
    PdfProtectionOutcome.Upgraded ->
        if (restrictionsKept) "Restrictions kept, re-locked with AES-256" else "Re-locked with AES-256"
    PdfProtectionOutcome.KeptNewOwner -> "Restrictions kept under a new owner password"
    PdfProtectionOutcome.OwnerPasswordOpens ->
        "Restrictions kept. The result opens with the password you entered."
    PdfProtectionOutcome.SavedWithoutProtection -> "Saved without protection"
}
