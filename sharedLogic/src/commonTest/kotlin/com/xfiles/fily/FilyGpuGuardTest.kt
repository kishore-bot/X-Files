package com.xfiles.fily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilyGpuGuardTest {
    private val untested = FilyGpuState.Untested
    private val trying = FilyGpuState.Trying
    private val proven = FilyGpuState.Proven
    private val bad = FilyGpuState.Bad

    @Test
    fun firstGpuLoadWritesTryingBeforeItRuns() {
        val start = FilyGpuPolicy.startLoad(untested, preferGpu = true)
        assertTrue(start.useGpu)
        assertEquals(trying, start.write)
    }

    @Test
    fun leftoverTryingMeansTheLastLaunchDiedAndGpuIsRuledOut() {
        val start = FilyGpuPolicy.startLoad(trying, preferGpu = true)
        assertFalse(start.useGpu)
        assertEquals(bad, start.write)
    }

    @Test
    fun leftoverTryingIsRecordedAsBadEvenWhenCpuIsRequested() {
        val start = FilyGpuPolicy.startLoad(trying, preferGpu = false)
        assertFalse(start.useGpu)
        assertEquals(bad, start.write)
    }

    @Test
    fun badStaysBadWithoutAWrite() {
        val start = FilyGpuPolicy.startLoad(bad, preferGpu = true)
        assertFalse(start.useGpu)
        assertNull(start.write)
    }

    @Test
    fun provenGpuLoadsWithoutOpeningTheCrashWindowAgain() {
        val start = FilyGpuPolicy.startLoad(proven, preferGpu = true)
        assertTrue(start.useGpu)
        assertNull(start.write)
    }

    @Test
    fun cpuRequestNeverTouchesStorageUnlessRecoveringTrying() {
        val start = FilyGpuPolicy.startLoad(untested, preferGpu = false)
        assertFalse(start.useGpu)
        assertNull(start.write)
    }

    @Test
    fun gpuAnswerProvesOnceAndFallbackMarksBad() {
        assertEquals(proven, FilyGpuPolicy.afterAnswer(trying, answeredOnGpu = true))
        assertNull(FilyGpuPolicy.afterAnswer(proven, answeredOnGpu = true))
        assertEquals(bad, FilyGpuPolicy.afterAnswer(trying, answeredOnGpu = false))
        assertEquals(bad, FilyGpuPolicy.afterAnswer(proven, answeredOnGpu = false))
        assertNull(FilyGpuPolicy.afterAnswer(bad, answeredOnGpu = false))
    }

    @Test
    fun wireNamesRoundTrip() {
        FilyGpuState.all.forEach { assertEquals(it, FilyGpuState.fromWire(it.wire)) }
        assertNull(FilyGpuState.fromWire("nope"))
    }

    @Test
    fun guardCrashSequenceLeavesBadForTheNextLaunch() {
        val prefs = MemoryPreferences()
        // Launch 1: GPU load starts, the process dies before any answer.
        assertTrue(FilyGpuGuard(prefs, "1-100").startLoad(preferGpu = true))
        // Launch 2 with the same build: the leftover "trying" disables the GPU.
        assertFalse(FilyGpuGuard(prefs, "1-100").startLoad(preferGpu = true))
        assertEquals(bad, FilyGpuGuard(prefs, "1-100").read())
    }

    @Test
    fun guardProvenStateSurvivesRelaunch() {
        val prefs = MemoryPreferences()
        FilyGpuGuard(prefs, "1-100").apply {
            assertTrue(startLoad(preferGpu = true))
            answered(answeredOnGpu = true)
        }
        assertTrue(FilyGpuGuard(prefs, "1-100").startLoad(preferGpu = true))
        assertEquals(proven, FilyGpuGuard(prefs, "1-100").read())
    }

    @Test
    fun newBuildStampOrModelFileGetsOneFreshGpuAttempt() {
        val prefs = MemoryPreferences()
        FilyGpuGuard(prefs, "1-100").apply {
            startLoad(preferGpu = true)
            failed()
        }
        assertFalse(FilyGpuGuard(prefs, "1-100").startLoad(preferGpu = true))
        // App update (version or install time changed): the Bad from the old build no longer applies.
        assertTrue(FilyGpuGuard(prefs, "2-200").startLoad(preferGpu = true))
        // Model file changed under the same app build.
        FilyGpuGuard(prefs, "2-200").failed()
        assertTrue(FilyGpuGuard(prefs, "2-300").startLoad(preferGpu = true))
    }

    @Test
    fun answerFromCpuFallbackRecordsBad() {
        val prefs = MemoryPreferences()
        val guard = FilyGpuGuard(prefs, "1-100")
        assertTrue(guard.startLoad(preferGpu = true))
        guard.answered(answeredOnGpu = false)
        assertEquals(bad, guard.read())
    }

    private class MemoryPreferences : FilyPreferences {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) {
            values[key] = value
        }
    }
}
