package com.xfiles.fily

interface FilyPreferences {
    fun get(key: String): String?
    fun put(key: String, value: String)

    /**
     * Writes to disk before returning, for state that must survive a native crash that follows. Blocking:
     * call it off the main thread.
     */
    fun putDurably(key: String, value: String) = put(key, value)
}
