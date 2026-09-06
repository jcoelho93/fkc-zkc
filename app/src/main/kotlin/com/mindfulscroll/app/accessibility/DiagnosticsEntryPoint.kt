package com.mindfulscroll.app.accessibility

import com.mindfulscroll.app.overlay.OverlayController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets instrumented tests pull the live singletons out of the real Hilt graph (via
 * EntryPointAccessors.fromApplication) without needing full Hilt test infrastructure
 * (HiltTestApplication, a custom test runner, @HiltAndroidTest). The real MindfulScrollApp
 * runs unmodified during instrumented tests, so its SingletonComponent is already fully
 * populated - this just exposes a way to read from it.
 *
 * This lives in `main`, not `debug`, specifically so the release (R8-minified) variant can be
 * instrumented too. The R8 keep rule for it is in proguard-rules.pro; the whole point of
 * testing the release variant is that R8 is the thing under suspicion, so the hook it needs
 * has to survive minification. It is an interface with no behaviour and nothing in the app
 * ever calls it, so shipping it costs a few bytes and no runtime work.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagnosticsEntryPoint {
    fun serviceDiagnostics(): ServiceDiagnostics

    fun overlayController(): OverlayController
}
