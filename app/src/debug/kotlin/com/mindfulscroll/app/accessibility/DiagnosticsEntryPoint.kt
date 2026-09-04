package com.mindfulscroll.app.accessibility

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Debug-build-only: lets instrumented tests pull ServiceDiagnostics out of the real Hilt
 * graph (via EntryPointAccessors.fromApplication) without needing full Hilt test
 * infrastructure (HiltTestApplication, a custom test runner, @HiltAndroidTest). The real
 * MindfulScrollApp runs unmodified during instrumented tests, so its SingletonComponent is
 * already fully populated - this just exposes a way to read from it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagnosticsEntryPoint {
    fun serviceDiagnostics(): ServiceDiagnostics
}
