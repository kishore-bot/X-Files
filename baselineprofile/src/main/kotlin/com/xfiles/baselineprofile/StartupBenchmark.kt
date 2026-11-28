package com.xfiles.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library cold start, without and with the Baseline Profile.
 * StartupTimingMetric also reports fully drawn timing: MainActivity reports drawn once recents load.
 *
 *   ANDROID_SERIAL=<serial> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 *
 * Emulator numbers only compare the two modes; absolute times need a real phone.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutProfile() = startup(CompilationMode.None())

    @Test
    fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun warmStartupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require), StartupMode.WARM)

    private fun startup(mode: CompilationMode, coldOrWarm: StartupMode = StartupMode.COLD) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = coldOrWarm,
        iterations = 8,
        setupBlock = { pressHome() },
    ) { startupJourney() }
}
