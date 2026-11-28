package com.xfiles.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Startup is narrow; frequent tools and actual readers contribute only to the Baseline Profile. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private lateinit var fixtures: BenchmarkFixtures

    @Before
    fun prepareFixtures() { fixtures = BenchmarkFixtures.create() }

    @After
    fun revokeFixtureAccess() { if (::fixtures.isInitialized) fixtures.revoke() }

    @Test
    fun generateStartup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) { startupJourney() }

    @Test
    fun generateFrequentTools() = rule.collect(packageName = PACKAGE, includeInStartupProfile = false) { coreJourney() }

    @Test
    fun generateTextReader() = rule.collect(packageName = PACKAGE, includeInStartupProfile = false) { readerJourney(fixtures.text) }

    @Test
    fun generatePdfReader() = rule.collect(packageName = PACKAGE, includeInStartupProfile = false) { readerJourney(fixtures.pdf) }
}
