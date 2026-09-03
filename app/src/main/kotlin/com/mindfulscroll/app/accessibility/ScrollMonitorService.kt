package com.mindfulscroll.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.data.entity.OverlayChoice
import com.mindfulscroll.app.data.repository.MonitoredAppRepository
import com.mindfulscroll.app.data.repository.ScrollStatsRepository
import com.mindfulscroll.app.overlay.OVERLAY_GRACE_MINUTES
import com.mindfulscroll.app.overlay.OverlayController
import com.mindfulscroll.app.overlay.OverlayUiState
import com.mindfulscroll.app.stats.SessionState
import com.mindfulscroll.app.stats.ThresholdConfig
import com.mindfulscroll.app.stats.ThresholdEvaluator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * See the accessibility/foreground-detection design note in the project README and commit
 * history: [android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] drives
 * live "which app is foreground" tracking, [android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED]
 * is only counted when it matches that foreground package AND that package is monitored, and
 * this app's own package is never eligible to be monitored - so browsing Mindful Scroll's own
 * dashboard/settings never counts as a "scroll."
 */
@AndroidEntryPoint
class ScrollMonitorService : AccessibilityService() {

    @Inject lateinit var monitoredAppRepository: MonitoredAppRepository

    @Inject lateinit var scrollStatsRepository: ScrollStatsRepository

    @Inject lateinit var overlayController: OverlayController

    private var serviceJob: Job? = null
    private lateinit var serviceScope: CoroutineScope

    /** packageName -> latest known config, refreshed reactively from Room. */
    private var monitoredApps: Map<String, MonitoredAppEntity> = emptyMap()

    private var currentForegroundPackage: String? = null
    private var lastForegroundChangeAtMillis: Long = System.currentTimeMillis()
    private var lastCountedScrollAtMillis: Long = 0L
    private var isOverlayShowing = false
    private var currentOverlayEventId: Long? = null
    private val graceUntilMillis = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val job = SupervisorJob()
        serviceJob = job
        serviceScope = CoroutineScope(job + Dispatchers.Main.immediate)

        monitoredAppRepository.observeMonitored()
            .onEach { apps -> monitoredApps = apps.associateBy { it.packageName } }
            .launchIn(serviceScope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                handleWindowStateChanged(event.packageName?.toString())
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                handleScroll(event.packageName?.toString())
        }
    }

    private fun isMonitored(packageName: String): Boolean =
        packageName != this.packageName && monitoredApps[packageName]?.isMonitored == true

    private fun handleWindowStateChanged(packageName: String?) {
        if (packageName.isNullOrEmpty() || packageName == currentForegroundPackage) return

        val now = System.currentTimeMillis()
        val previousPackage = currentForegroundPackage
        val previousStart = lastForegroundChangeAtMillis
        currentForegroundPackage = packageName
        lastForegroundChangeAtMillis = now
        lastCountedScrollAtMillis = 0L

        serviceScope.launch {
            if (previousPackage != null && isMonitored(previousPackage)) {
                scrollStatsRepository.addForegroundTime(previousPackage, now - previousStart, now)
                scrollStatsRepository.clearSession(previousPackage)
            }
            if (isMonitored(packageName)) {
                scrollStatsRepository.startSession(packageName, now)
            }
        }
    }

    private fun handleScroll(packageName: String?) {
        if (packageName.isNullOrEmpty()) return
        if (packageName != currentForegroundPackage) return
        if (!isMonitored(packageName)) return
        if (isOverlayShowing) return

        val now = System.currentTimeMillis()
        if (now - lastCountedScrollAtMillis < SCROLL_DEBOUNCE_MILLIS) return
        lastCountedScrollAtMillis = now

        serviceScope.launch {
            val session = scrollStatsRepository.recordScroll(packageName, now)
            val config = monitoredApps[packageName] ?: return@launch

            val result = ThresholdEvaluator.evaluate(
                session = SessionState(session.scrollCountInSession, session.sessionStartMillis),
                config = ThresholdConfig(config.scrollThreshold, config.timeThresholdMinutes),
                nowMillis = now,
            )

            val grace = graceUntilMillis[packageName]
            val graceExpired = grace != null && now >= grace

            if (result.crossed || graceExpired) {
                graceUntilMillis.remove(packageName)
                showOverlay(config, session.scrollCountInSession, now - session.sessionStartMillis)
            }
        }
    }

    private suspend fun showOverlay(app: MonitoredAppEntity, scrollCount: Int, sessionElapsedMillis: Long) {
        val now = System.currentTimeMillis()
        isOverlayShowing = true
        currentOverlayEventId = scrollStatsRepository.recordOverlayShown(
            packageName = app.packageName,
            nowMillis = now,
            scrollCountAtTrigger = scrollCount,
            sessionTimeMillisAtTrigger = sessionElapsedMillis,
        )

        overlayController.show(
            state = OverlayUiState(
                appLabel = app.appLabel,
                scrollCount = scrollCount,
                sessionMinutes = (sessionElapsedMillis / 60_000L).toInt(),
                frictionMode = app.frictionMode,
            ),
            onCloseApp = {
                serviceScope.launch { resolveOverlay(app.packageName, OverlayChoice.CLOSE_APP) }
                performGlobalAction(GLOBAL_ACTION_HOME)
            },
            onContinue = {
                serviceScope.launch { resolveOverlay(app.packageName, OverlayChoice.CONTINUE) }
            },
        )
    }

    private suspend fun resolveOverlay(packageName: String, choice: OverlayChoice) {
        val now = System.currentTimeMillis()
        currentOverlayEventId?.let { scrollStatsRepository.recordOverlayChoice(it, choice, now) }
        currentOverlayEventId = null
        isOverlayShowing = false
        overlayController.hide()

        if (choice == OverlayChoice.CONTINUE) {
            scrollStatsRepository.startSession(packageName, now)
            graceUntilMillis[packageName] = now + OVERLAY_GRACE_MINUTES * 60_000L
        }
    }

    override fun onInterrupt() {
        overlayController.hide()
        isOverlayShowing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayController.hide()
        serviceJob?.cancel()
    }

    private companion object {
        /** Treat scroll events for the same app within this window as one logical swipe. */
        const val SCROLL_DEBOUNCE_MILLIS = 300L
    }
}
