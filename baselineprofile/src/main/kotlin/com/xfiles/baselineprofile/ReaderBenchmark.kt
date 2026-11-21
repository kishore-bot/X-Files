package com.xfiles.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled reader scrolling; provider setup and fixture decoding finish before frame measurement. */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()
    private lateinit var fixtures: BenchmarkFixtures

    @Before
    fun prepareFixtures() { fixtures = BenchmarkFixtures.create() }

    @After
    fun revokeFixtureAccess() { if (::fixtures.isInitialized) fixtures.revoke() }

    @Test
    fun textWithoutProfile() = scrolling(false, CompilationMode.None())

    @Test
    fun textWithProfile() = scrolling(false, CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun pdfWithoutProfile() = scrolling(true, CompilationMode.None())

    @Test
    fun pdfWithProfile() = scrolling(true, CompilationMode.Partial(BaselineProfileMode.Require))

    private fun scrolling(pdf: Boolean, mode: CompilationMode) {
        val fixture = if (pdf) fixtures.pdf else fixtures.text
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = mode,
            startupMode = null,
            iterations = 8,
            setupBlock = { openReaderFixture(fixture) },
        ) { scrollReaderFixture(fixture) }
    }
}
