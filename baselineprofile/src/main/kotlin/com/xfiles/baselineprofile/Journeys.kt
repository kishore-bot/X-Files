package com.xfiles.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

const val PACKAGE = "com.xfiles"
private const val WAIT_MS = 5_000L

/** Startup Profile contains only the useful library launch. A missing screen fails collection. */
fun MacrobenchmarkScope.startupJourney() {
    pressHome()
    startActivityAndWait()
    requireObject(By.pkg(PACKAGE).text("Tools"), "library Tools nav item")
    requireObject(By.pkg(PACKAGE).text("Open file"), "library Open file action")
    device.waitForIdle()
}

/** Frequent tool code belongs in the Baseline Profile, outside the Startup Profile. */
fun MacrobenchmarkScope.coreJourney() {
    startupJourney()
    requireObject(By.pkg(PACKAGE).text("Tools"), "Tools nav item").click()
    device.waitForIdle()
    requireObject(By.pkg(PACKAGE).text("Tidy up"), "Tools hub")
    device.pressBack()
    requireObject(By.pkg(PACKAGE).text("Tools"), "library after closing Tools")
    device.waitForIdle()
}

/** A test-owned provider grants the fixture explicitly; production picker permissions stay intact. */
fun MacrobenchmarkScope.openReaderFixture(fixture: ReaderFixture) {
    startActivityAndWait(fixture.launchIntent())
    if (fixture.pdf) {
        requireObject(By.pkg(PACKAGE).desc("Page 1"), "rendered native PDF page")
    } else {
        requireObject(By.pkg(PACKAGE).textContains(TEXT_MARKER), "parsed native text fixture")
    }
    device.waitForIdle()
}

/** Six finite gestures exercise actual reader content and assert the viewport changed. */
fun MacrobenchmarkScope.scrollReaderFixture(fixture: ReaderFixture) {
    repeat(6) {
        check(device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 3, 18)) { "Reader swipe was not injected" }
        device.waitForIdle()
    }
    val initial = if (fixture.pdf) By.pkg(PACKAGE).desc("Page 1") else By.pkg(PACKAGE).textContains(TEXT_MARKER)
    check(device.wait(Until.gone(initial), WAIT_MS)) { "Fixture reader did not scroll away from its initial content" }
    check(device.hasObject(By.pkg(PACKAGE).desc("Back to library"))) { "Reader disappeared during scrolling" }
}

fun MacrobenchmarkScope.readerJourney(fixture: ReaderFixture) {
    openReaderFixture(fixture)
    scrollReaderFixture(fixture)
    device.pressBack()
    requireObject(By.pkg(PACKAGE).text("Tools"), "library after closing fixture")
}

private fun MacrobenchmarkScope.requireObject(selector: BySelector, description: String): UiObject2 =
    checkNotNull(device.wait(Until.findObject(selector), WAIT_MS)) { "Missing $description in $PACKAGE" }
