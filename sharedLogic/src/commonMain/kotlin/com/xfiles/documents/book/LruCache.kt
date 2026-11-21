package com.xfiles.documents.book

/**
 * A least-recently-used cache of at most [capacity] entries. A read moves the entry to the front by
 * re-inserting it into the insertion-ordered map, so the eldest key is always the one to evict.
 * Not thread-safe: use it from one thread.
 */
class LruCache<K, V>(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val entries = LinkedHashMap<K, V>()

    val size: Int
        get() = entries.size

    operator fun contains(key: K): Boolean = entries.containsKey(key)

    fun get(key: K): V? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    fun put(key: K, value: V) {
        entries.remove(key)
        entries[key] = value
        while (entries.size > capacity) {
            entries.remove(entries.keys.first())
        }
    }

    fun remove(key: K): V? = entries.remove(key)

    fun clear() {
        entries.clear()
    }
}
