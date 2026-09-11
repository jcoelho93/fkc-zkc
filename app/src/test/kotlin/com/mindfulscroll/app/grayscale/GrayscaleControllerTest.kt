package com.mindfulscroll.app.grayscale

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.accessibility.ServiceDiagnostics
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * GrayscaleController against real SharedPreferences and Robolectric's Settings.Secure: the part
 * GrayscalePolicyTest cannot reach, where the "we applied it" record has to outlive the object
 * that wrote it. A fresh controller over the same storage stands in for the process that died.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class GrayscaleControllerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val resolver get() = app.contentResolver
    private lateinit var diagnostics: ServiceDiagnostics

    @Before
    fun setUp() {
        diagnostics = ServiceDiagnostics()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putInt(resolver, DaltonizerSettings.KEY_ENABLED, 0)
        Settings.Secure.putString(resolver, DaltonizerSettings.KEY_MODE, DEUTERANOMALY.toString())
    }

    private fun newController() = GrayscaleController(app, diagnostics)

    @Test
    fun `entering applies grayscale and leaving puts the user's mode back`() {
        val controller = newController()

        controller.onForeground("com.instagram.android", wantGrayscale = true)
        assertThat(DaltonizerSettings.read(resolver)).isEqualTo(DaltonizerState.GRAYSCALE)
        assertThat(controller.appliedRecord()).isEqualTo(AppliedGrayscale(DEUTERANOMALY))
        assertThat(diagnostics.state.value.grayscaleAppliedCount).isEqualTo(1)

        controller.onForeground("com.android.launcher", wantGrayscale = false)
        assertThat(DaltonizerSettings.read(resolver)).isEqualTo(DaltonizerState(enabled = false, mode = DEUTERANOMALY))
        assertThat(controller.appliedRecord()).isNull()
        assertThat(diagnostics.state.value.grayscaleRestoredCount).isEqualTo(1)
        assertThat(diagnostics.state.value.lastGrayscaleError).isNull()
    }

    @Test
    fun `a mode that never existed is cleared on restore rather than left at grayscale`() {
        Settings.Secure.putString(resolver, DaltonizerSettings.KEY_MODE, null)
        val controller = newController()

        controller.onForeground("com.instagram.android", wantGrayscale = true)
        controller.onForeground("com.android.launcher", wantGrayscale = false)

        assertThat(DaltonizerSettings.read(resolver)).isEqualTo(DaltonizerState(enabled = false, mode = null))
    }

    @Test
    fun `grayscale left on by a process that died is restored by the next one`() {
        newController().onForeground("com.instagram.android", wantGrayscale = true)
        // The service dies here: nothing calls restore, and the screen stays gray.
        assertThat(DaltonizerSettings.read(resolver).isGrayscale).isTrue()

        newController().restoreIfApplied("service connected")

        assertThat(DaltonizerSettings.read(resolver)).isEqualTo(DaltonizerState(enabled = false, mode = DEUTERANOMALY))
        assertThat(newController().appliedRecord()).isNull()
    }

    @Test
    fun `the user's own colour correction is left alone and the skip is named`() {
        Settings.Secure.putInt(resolver, DaltonizerSettings.KEY_ENABLED, 1)
        val controller = newController()

        controller.onForeground("com.instagram.android", wantGrayscale = true)
        controller.onForeground("com.android.launcher", wantGrayscale = false)
        controller.restoreIfApplied("service destroyed")

        assertThat(DaltonizerSettings.read(resolver)).isEqualTo(DaltonizerState(enabled = true, mode = DEUTERANOMALY))
        assertThat(controller.appliedRecord()).isNull()
        assertThat(diagnostics.state.value.lastGrayscaleSkip).contains("already on")
    }

    @Test
    fun `without the permission nothing is written and Diagnostics says why`() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val controller = newController()

        controller.onForeground("com.instagram.android", wantGrayscale = true)

        assertThat(DaltonizerSettings.read(resolver)).isEqualTo(DaltonizerState(enabled = false, mode = DEUTERANOMALY))
        assertThat(controller.appliedRecord()).isNull()
        assertThat(diagnostics.state.value.lastGrayscaleSkip).contains("WRITE_SECURE_SETTINGS")
        assertThat(diagnostics.state.value.grayscaleAppliedCount).isEqualTo(0)
    }

    @Test
    fun `losing the permission while grayscale is on is an error, and the record is kept to retry`() {
        val controller = newController()
        controller.onForeground("com.instagram.android", wantGrayscale = true)
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)

        controller.onForeground("com.android.launcher", wantGrayscale = false)

        assertThat(diagnostics.state.value.lastGrayscaleError).contains("revoked")
        assertThat(controller.appliedRecord()).isNotNull()
    }

    @Test
    fun `colour correction changed by the user while ours was on is forgotten, not switched off`() {
        val controller = newController()
        controller.onForeground("com.instagram.android", wantGrayscale = true)
        Settings.Secure.putString(resolver, DaltonizerSettings.KEY_MODE, DEUTERANOMALY.toString())

        controller.onForeground("com.android.launcher", wantGrayscale = false)

        assertThat(DaltonizerSettings.read(resolver)).isEqualTo(DaltonizerState(enabled = true, mode = DEUTERANOMALY))
        assertThat(controller.appliedRecord()).isNull()
    }

    private companion object {
        const val DEUTERANOMALY = 12
    }
}
