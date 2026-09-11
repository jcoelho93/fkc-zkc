package com.mindfulscroll.app

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Grayscale (#27) end to end on a real device: the permission granted over adb, a real foreground
 * transition into a monitored app, and the system's own colour-correction settings read back with
 * `settings get` - not the app's record of what it wrote.
 *
 * Lives in `androidTest`, so it also runs against the R8-minified release build. The settings
 * writes happen inside the minified accessibility service, and "the switch does nothing in the
 * shipped APK" would fail exactly as silently as every other bug this project has had.
 *
 * `adb screencap` cannot see the result (the colour matrix is applied after the screenshot
 * buffer), so the settings are the evidence.
 *
 * The permission is granted and deliberately NOT revoked afterwards: revoking a permission kills
 * the app's process, and the instrumentation runs inside it. Leaving it granted is harmless to the
 * rest of the suite, since grayscale only acts for an app that has it switched on, and teardown
 * empties the monitored list.
 */
@RunWith(AndroidJUnit4::class)
class GrayscaleInstrumentedTest {

    private lateinit var harness: AccessibilityServiceHarness
    private val monitoredPackage = "com.android.settings"
    private var originalEnabled: String = "null"
    private var originalMode: String = "null"

    @Before
    fun setUp() {
        harness = AccessibilityServiceHarness()
        // `settings get` prints the literal "null" for a key that has never been written.
        originalEnabled = harness.shell("settings get secure $KEY_ENABLED")
        originalMode = harness.shell("settings get secure $KEY_MODE")

        val packageName = harness.targetContext.packageName
        Log.i(TAG, "pm grant: " + harness.shell("pm grant $packageName ${Manifest.permission.WRITE_SECURE_SETTINGS}"))
        assertEquals(
            "WRITE_SECURE_SETTINGS was not granted by `pm grant`, so nothing below can work. Is it " +
                "still declared in AndroidManifest.xml?",
            PackageManager.PERMISSION_GRANTED,
            harness.targetContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS),
        )

        runBlocking {
            harness.monitoredAppRepository.applySelection(
                listOf(
                    MonitoredAppEntity(
                        packageName = monitoredPackage,
                        appLabel = "Settings",
                        isMonitored = true,
                        scrollThreshold = 40,
                        timeThresholdMinutes = 10,
                        addedAtMillis = System.currentTimeMillis(),
                        grayscaleEnabled = true,
                    ),
                ),
            )
            // applySelection keeps an existing row as it was, so set the switch explicitly too.
            harness.monitoredAppRepository.setGrayscaleEnabled(monitoredPackage, true)
        }
    }

    @After
    fun tearDown() {
        if (!::harness.isInitialized) return
        harness.pressHome()
        runBlocking { harness.monitoredAppRepository.applySelection(emptyList()) }
        harness.disableService()
        // Let the service finish its own restore before the originals are written back, so the
        // two cannot interleave and leave the emulator in a colour mode nobody chose.
        harness.pollUntil(5_000, "service-disconnected") { !harness.diagnostics.state.value.isServiceConnected }
        putOrDelete(KEY_ENABLED, originalEnabled)
        putOrDelete(KEY_MODE, originalMode)
    }

    @Test
    fun grayscaleComesOnInTheMonitoredAppAndTheUsersModeComesBackOnLeaving() {
        // A real previous mode, so "restored" is distinguishable from "switched off".
        harness.shell("settings put secure $KEY_ENABLED 0")
        harness.shell("settings put secure $KEY_MODE $DEUTERANOMALY")
        connectAndAwaitMonitoredList()

        openMonitoredApp()
        val applied = harness.pollUntil(10_000, "grayscale-on") { daltonizer() == "1" to "0" }
        assertTrue(
            "Entering $monitoredPackage with grayscale switched on did not turn colour correction on " +
                "in grayscale mode: the system reads ${daltonizer()}. ${describeState()}",
            applied,
        )

        harness.pressHome()
        val restored = harness.pollUntil(10_000, "grayscale-off") { daltonizer() == "0" to "$DEUTERANOMALY" }
        assertTrue(
            "Leaving $monitoredPackage did not put colour correction back to off in the user's " +
                "previous mode ($DEUTERANOMALY): the system reads ${daltonizer()}. ${describeState()}",
            restored,
        )

        val state = harness.diagnostics.state.value
        assertTrue("apply was not counted as read back. ${describeState()}", state.grayscaleAppliedCount >= 1)
        assertTrue("restore was not counted as read back. ${describeState()}", state.grayscaleRestoredCount >= 1)
        assertEquals("grayscale reported an error. ${describeState()}", null, state.lastGrayscaleError)
    }

    @Test
    fun colourCorrectionTheUserAlreadyHadOnIsNeverTouched() {
        harness.shell("settings put secure $KEY_MODE $DEUTERANOMALY")
        harness.shell("settings put secure $KEY_ENABLED 1")
        connectAndAwaitMonitoredList()

        openMonitoredApp()
        // Sync point: the service has made its decision once it records the skip.
        assertTrue(
            "The service never recorded skipping grayscale over the user's own colour correction. " +
                describeState(),
            harness.pollUntil(10_000, "grayscale-skip") {
                harness.diagnostics.state.value.lastGrayscaleSkip?.contains("already on") == true
            },
        )
        assertEquals(
            "Grayscale overwrote colour correction the user had on. ${describeState()}",
            "1" to "$DEUTERANOMALY",
            daltonizer(),
        )

        harness.pressHome()
        harness.pollUntil(10_000, "left-app") { harness.diagnostics.state.value.currentForegroundPackage != monitoredPackage }
        assertEquals(
            "Leaving the app switched off colour correction this app never turned on. ${describeState()}",
            "1" to "$DEUTERANOMALY",
            daltonizer(),
        )
    }

    private fun connectAndAwaitMonitoredList() {
        assertTrue("service never connected", harness.enableServiceAndAwaitConnection())
        assertTrue(
            "service never picked up $monitoredPackage as monitored",
            harness.pollUntil(10_000, "monitored-list") {
                monitoredPackage in harness.diagnostics.state.value.monitoredPackages
            },
        )
    }

    private fun openMonitoredApp() {
        Log.i(TAG, "am start: " + harness.shell("am start -W -a android.settings.SETTINGS"))
        assertTrue(
            "$monitoredPackage never came to the foreground, so there was no transition to act on. " +
                describeState(),
            harness.pollUntil(15_000, "foreground") {
                harness.diagnostics.state.value.currentForegroundPackage == monitoredPackage
            },
        )
    }

    /** (enabled, mode), exactly as the system stores them. */
    private fun daltonizer(): Pair<String, String> =
        harness.shell("settings get secure $KEY_ENABLED") to harness.shell("settings get secure $KEY_MODE")

    private fun putOrDelete(key: String, value: String) {
        if (value == "null") harness.shell("settings delete secure $key") else harness.shell("settings put secure $key $value")
    }

    private fun describeState(): String {
        val s = harness.diagnostics.state.value
        return "foreground=${s.currentForegroundPackage} applied=${s.grayscaleAppliedCount} " +
            "restored=${s.grayscaleRestoredCount} lastApply=${s.lastGrayscaleApply} " +
            "lastRestore=${s.lastGrayscaleRestore} lastSkip=${s.lastGrayscaleSkip} " +
            "lastError=${s.lastGrayscaleError} log=${s.recentLog.take(15)}"
    }

    private companion object {
        const val TAG = "GrayscaleTest"
        const val KEY_ENABLED = "accessibility_display_daltonizer_enabled"
        const val KEY_MODE = "accessibility_display_daltonizer"
        const val DEUTERANOMALY = 12
    }
}
