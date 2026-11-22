package com.xfiles.core.documents

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets caches give memory back when the system asks. The application calls [trim] from `onTrimMemory` when the
 * UI goes to the background or memory runs low, so an unused page or thumbnail cache does not get the whole app
 * killed. Listeners are copy-on-write, so one may unregister itself while the trim runs.
 */
object MemoryPressure {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Adds [onTrim]; call the returned function to remove it again. */
    fun register(onTrim: () -> Unit): () -> Unit {
        listeners += onTrim
        return { listeners -= onTrim }
    }

    fun trim() {
        for (listener in listeners) listener()
    }
}
