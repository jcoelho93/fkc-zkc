package com.mindfulscroll.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
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
 * See the accessibility/foreground-detection design note in the project README: live
 * "which app is foreground" tracking comes from TYPE_WINDOW_STATE_CHANGED, and scroll
 * activity is only counted when it matches that foreground package AND that package is
 * monitored (this app's own package is never eligible, so browsing our own UI never counts).
 *
 * Scroll signal: TYPE_VIEW_SCROLLED is a legacy View.scrollBy()-driven event that
 * ScrollView/ListView fire reliably but RecyclerView (most feed apps) and Compose's
 * LazyColumn often don't, since they move child views directly instead of calling
 * View.scrollBy(). TYPE_WINDOW_CONTENT_CHANGED (new items binding in as you scroll a feed)
 * is a noisier but far more reliable fallback signal for those apps - both are treated as
 * "one scroll" candidates through the same debounce, so this doesn't double count when
 * TYPE_VIEW_SCROLLED *does* fire. See ServiceDiagnostics / the in-app Diagnostics screen to
 * check which signal (if either) a given app is actually sending.
 */
@AndroidEntryPoint
class ScrollMonitorService : AccessibilityService() {

    @Inject lateinit var monitoredAppRepository: MonitoredAppRepository

    @Inject lateinit var scrollStatsRepository: ScrollStatsRepository

    @Inject lateinit var overlayController: OverlayController

    @Inject lateinit var diagnostics: ServiceDiagnostics

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

        diagnostics.update { it.copy(serviceConnectedAtMillis = System.currentTimeMillis()) }
        diagnostics.log("Service connected")
        Log.d(TAG, "onServiceConnected")

        monitoredAppRepository.observeMonitored()
            .onEach { apps ->
                monitoredApps = apps.associateBy { it.packageName }
                diagnostics.update { it.copy(monitoredPackages = monitoredApps.keys) }
                diagnostics.log("Monitored apps updated: ${monitoredApps.keys}")
                Log.d(TAG, "Monitored apps: ${monitoredApps.keys}")
            }
            .launchIn(serviceScope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event.packageName?.toString())
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                diagnostics.update { it.copy(rawScrollEventCount = it.rawScrollEventCount + 1) }
                handleScroll(event.packageName?.toString(), source = "TYPE_VIEW_SCROLLED")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                diagnostics.update { it.copy(rawContentChangedEventCount = it.rawContentChangedEventCount + 1) }
                handleScroll(event.packageName?.toString(), source = "TYPE_WINDOW_CONTENT_CHANGED")
            }
        }
    }

    private fun isMonitored(packageName: String): Boolean =
        packageName != this.packageName && monitoredApps[packageName]?.isMonitored == true

    private fun handleWindowStateChanged(packageName: String?) {
        if (packageName.isNullOrEmpty() || packageName == currentForegroundPackage) return

        Log.d(TAG, "Foreground changed: $currentForegroundPackage -> $packageName")
        val now = System.currentTimeMillis()
        val previousPackage = currentForegroundPackage
        val previousStart = lastForegroundChangeAtMillis
        currentForegroundPackage = packageName
        lastForegroundChangeAtMillis = now
        lastCountedScrollAtMillis = 0L
        diagnostics.update { it.copy(currentForegroundPackage = packageName) }
        diagnostics.log("Foreground -> $packageName")

        serviceScope.launch {
            if (previousPackage != null && isMonitored(previousPackage)) {
                scrollStatsRepository.addForegroundTime(previousPackage, now - previousStart, now)
                scrollStatsRepository.clearSession(previousPackage)
                diagnostics.update { it.copy(activeSessionPackage = null, activeSessionScrollCount = 0, activeSessionStartMillis = null) }
            }
            if (isMonitored(packageName)) {
                scrollStatsRepository.startSession(packageName, now)
                diagnostics.update {
                    it.copy(activeSessionPackage = packageName, activeSessionScrollCount = 0, activeSessionStartMillis = now)
                }
            }
        }
    }

    private fun handleScroll(packageName: String?, source: String) {
        if (packageName.isNullOrEmpty()) return
        if (packageName != currentForegroundPackage) return
        if (!isMonitored(packageName)) return
        if (isOverlayShowing) return

        val now = System.currentTimeMillis()
        if (now - lastCountedScrollAtMillis < SCROLL_DEBOUNCE_MILLIS) return
        lastCountedScrollAtMillis = now

        diagnostics.update { it.copy(countedScrollTicks = it.countedScrollTicks + 1) }

        serviceScope.launch {
            val session = scrollStatsRepository.recordScroll(packageName, now)
            diagnostics.update {
                it.copy(activeSessionPackage = packageName, activeSessionScrollCount = session.scrollCountInSession)
            }
            diagnostics.log("Scroll #${session.scrollCountInSession} in $packageName (via $source)")
            Log.d(TAG, "Scroll #${session.scrollCountInSession} in $packageName via $source")

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
                diagnostics.log("Threshold crossed for $packageName (reasons=${result.reasons}, graceExpired=$graceExpired) - showing overlay")
                Log.d(TAG, "Threshold crossed for $packageName: reasons=${result.reasons} graceExpired=$graceExpired")
                showOverlay(config, session.scrollCountInSession, now - session.sessionStartMillis)
            }
        }
    }

    private suspend fun showOverlay(app: MonitoredAppEntity, scrollCount: Int, sessionElapsedMillis: Long) {
        val now = System.currentTimeMillis()
        isOverlayShowing = true
        diagnostics.update { it.copy(overlaysShownCount = it.overlaysShownCount + 1) }
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
        diagnostics.log("Overlay resolved for $packageName: $choice")

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
        diagnostics.update { it.copy(serviceConnectedAtMillis = null) }
        diagnostics.log("Service destroyed")
    }

    private companion object {
        const val TAG = "MindfulScroll"

        /** Treat scroll events for the same app within this window as one logical swipe. */
        const val SCROLL_DEBOUNCE_MILLIS = 300L
    }
}
