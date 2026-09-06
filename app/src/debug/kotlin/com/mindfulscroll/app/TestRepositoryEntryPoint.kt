package com.mindfulscroll.app

import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.data.repository.IntentionRepository
import com.mindfulscroll.app.data.repository.MonitoredAppRepository
import com.mindfulscroll.app.data.repository.ScrollStatsRepository
import com.mindfulscroll.app.intention.IntentionPromptController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Debug-only companion to DiagnosticsEntryPoint, kept separate on purpose. DiagnosticsEntryPoint
 * lives in `main` so the release variant can be instrumented, and every type it exposes has to
 * survive R8 and earn a keep rule. These are write-side handles that only debug tests need, so
 * putting them here keeps the release build's keep-rule surface as small as it is.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TestRepositoryEntryPoint {
    fun monitoredAppRepository(): MonitoredAppRepository

    fun scrollStatsRepository(): ScrollStatsRepository

    fun intentionRepository(): IntentionRepository

    fun appSettings(): AppSettings

    fun intentionPromptController(): IntentionPromptController
}
