package com.xfiles.core.documents

/**
 * Decodes the MS-DOS date and time stored in ZIP headers into epoch millis in
 * local time, which is how ZIP writers record them. Returns null when the date
 * field is zero (the archive stores no time).
 */
fun dosDateTimeMillis(modifiedDos: Int): Long? {
    val time = modifiedDos and 0xFFFF
    val date = (modifiedDos ushr 16) and 0xFFFF
    if (date == 0) return null
    val year = 1980 + (date shr 9)
    val month = ((date shr 5) and 0xF).coerceIn(1, 12)
    val day = (date and 0x1F).coerceIn(1, 31)
    val hour = ((time shr 11) and 0x1F).coerceIn(0, 23)
    val minute = ((time shr 5) and 0x3F).coerceIn(0, 59)
    val second = ((time and 0x1F) * 2).coerceIn(0, 59)
    return java.util.GregorianCalendar(year, month - 1, day, hour, minute, second).timeInMillis
}
