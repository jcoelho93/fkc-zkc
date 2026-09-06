package com.mindfulscroll.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.data.entity.OverlayChoice
import com.mindfulscroll.app.data.repository.IntentionRepository
import com.mindfulscroll.app.data.repository.MonitoredAppRepository
import com.mindfulscroll.app.data.repository.ScrollStatsRepository
import com.mindfulscroll.app.intention.IntentionPromptController
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
import kotlinx.coroutines.delay
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
 * LazyColumn don't - confirmed empirically by ScrollEventDetectionTest, which got zero of
 * either TYPE_VIEW_SCROLLED or the TYPE_WINDOW_CONTENT_CHANGED fallback when scrolling a
 * Compose LazyColumn. Both are still listened for (harmless, and may help for
 * legacy-View-based feeds), but scroll count can't be trusted as the primary signal for
 * Compose-heavy apps - which is why the time-based half of the threshold is checked
 * independently below rather than only ever being evaluated when a scroll event happens to
 * arrive. See ServiceDiagnostics / the in-app Diagnostics screen for live event counts.
 */
@AndroidEntryPoint
class ScrollMonitorService : AccessibilityService() {

    @Inject lateinit var monitoredAppRepository: MonitoredAppRepository

    @Inject lateinit var scrollStatsRepository: ScrollStatsRepository

    @Inject lateinit var overlayController: OverlayController

    @Inject lateinit var intentionPromptController: IntentionPromptController

    @Inject lateinit var intentionRepository: IntentionRepository

    @Inject lateinit var appSettings: AppSettings

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

    /** Last time the intention prompt was shown per package, for the app-switching debounce. */
    private val lastIntentionPromptAtMillis = mutableMapOf<String, Long>()

    /** Row id of the prompt currently on screen, so its answer updates the right record. */
    private var currentIntentionId: Long? = null

    /** Guarantees the threshold is checked even if no scroll event ever arrives for this session. */
    private var pendingThresholdCheckJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val job = SupervisorJob()
        serviceJob = job
        serviceScope = CoroutineScope(job + Dispatchers.Main.immediate)

        // getServiceInfo() is the system's parsed view of accessibility_service_config.xml, which
        // is not necessarily what the XML says - and it is the mask AccessibilityManagerService
        // actually dispatches against. A connected service with an empty event mask receives
        // nothing while looking perfectly healthy, so record it rather than assume it.
        val resolved = serviceInfo?.let { info ->
            "eventTypes=0x${info.eventTypes.toString(16)} feedbackType=0x${info.feedbackType.toString(16)} " +
                "flags=0x${info.flags.toString(16)} notificationTimeout=${info.notificationTimeout} " +
                "packageNames=${info.packageNames?.toList()}"
        } ?: "getServiceInfo() returned null"

        diagnostics.update {
            it.copy(
                serviceConnectedAtMillis = System.currentTimeMillis(),
                resolvedServiceInfo = resolved,
                resolvedEventTypes = serviceInfo?.eventTypes ?: 0,
            )
        }
        // TYPE_ACCESSIBILITY_OVERLAY windows are only permitted from this service's own context,
        // so the controller cannot get one by injection - it has to be handed ours while we live.
        overlayController.attach(this)
        intentionPromptController.attach(this)

        diagnostics.log("Service connected")
        diagnostics.log("Resolved serviceInfo: $resolved")
        Log.d(TAG, "onServiceConnected - resolved serviceInfo: $resolved")

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
        // Counted before the `when` on purpose: "never called" and "called, then filtered out"
        // are indistinguishable in every other counter, and they have opposite fixes.
        diagnostics.update { it.copy(totalEventCount = it.totalEventCount + 1) }

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

        // Our own overlay windows raise TYPE_WINDOW_STATE_CHANGED under THIS package, and without
        // this guard the service reads that as "the user left the monitored app": it closes the
        // session, banks the foreground time and resets the threshold clock - triggered by nothing
        // but us drawing on screen. Harmless for the interruption overlay, which is followed by a
        // session reset anyway, but fatal for the intention prompt, which appears at the START of
        // every session and would therefore destroy the very session its answer is filed against.
        // Scoped to "while one of our windows is up" so genuinely opening Mindful Scroll still
        // ends the previous app's session normally.
        if (packageName == this.packageName &&
            (overlayController.isShowing() || intentionPromptController.isShowing())
        ) {
            return
        }

        // The prompt belongs to the app that was in front; it must not linger over the next one.
        intentionPromptController.hide()
        currentIntentionId = null

        Log.d(TAG, "Foreground changed: $currentForegroundPackage -> $packageName")
        val now = System.currentTimeMillis()
        val previousPackage = currentForegroundPackage
        val previousStart = lastForegroundChangeAtMillis
        currentForegroundPackage = packageName
        lastForegroundChangeAtMillis = now
        lastCountedScrollAtMillis = 0L
        pendingThresholdCheckJob?.cancel()
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
                monitoredApps[packageName]?.let { maybeShowIntentionPrompt(it, sessionStartMillis = now) }
                scheduleThresholdCheck(packageName)
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

            checkThresholdAndMaybeShowOverlay(packageName)
        }
    }

    /**
     * Shows the "what are you hoping to find?" prompt for an app that has just come forward.
     *
     * This runs on every open rather than at a threshold, so everything here is shaped by not
     * costing the user anything: it never blocks the app (see IntentionPromptController), it is
     * skipped while the pause screen is up, and it is debounced so flicking between two apps does
     * not produce a prompt per switch.
     */
    private suspend fun maybeShowIntentionPrompt(app: MonitoredAppEntity, sessionStartMillis: Long) {
        if (!appSettings.intentionCaptureEnabledNow()) return
        if (isOverlayShowing) return

        val now = System.currentTimeMillis()
        val lastShown = lastIntentionPromptAtMillis[app.packageName]
        if (lastShown != null && now - lastShown < INTENTION_PROMPT_DEBOUNCE_MILLIS) {
            diagnostics.log("Intention prompt skipped for ${app.packageName}: shown ${(now - lastShown) / 1000}s ago")
            return
        }
        lastIntentionPromptAtMillis[app.packageName] = now

        val shown = intentionPromptController.show(
            appLabel = app.appLabel,
            onAnswer = { kind, note -> recordIntentionAnswer(app.packageName, kind, note) },
            onDismiss = {
                intentionPromptController.hide()
                currentIntentionId = null
            },
        )

        if (!shown) {
            diagnostics.log(
                "Intention prompt FAILED to display for ${app.packageName}: " +
                    "${intentionPromptController.lastFailureReason}",
            )
            Log.e(TAG, "Intention prompt failed for ${app.packageName}: ${intentionPromptController.lastFailureReason}")
            return
        }

        // Written only AFTER the window is up, so the table never contains a prompt nobody saw.
        // "Shown and ignored" is a real answer the weekly report needs to count; "never appeared"
        // is a bug, and mixing the two would quietly corrupt every rate computed from this table.
        currentIntentionId = intentionRepository.recordPromptShown(app.packageName, sessionStartMillis, now)
    }

    private fun recordIntentionAnswer(packageName: String, kind: IntentionKind, note: String?) {
        val intentionId = currentIntentionId
        intentionPromptController.hide()
        currentIntentionId = null
        if (intentionId == null) {
            // Only reachable if the user out-raced the insert above. Logged rather than ignored:
            // silently dropping answers would show up as a mysteriously low response rate.
            diagnostics.log("Intention answer for $packageName arrived before its row existed - dropped")
            return
        }
        serviceScope.launch {
            intentionRepository.recordAnswer(intentionId, kind, note, System.currentTimeMillis())
            diagnostics.update { it.copy(intentionsAnsweredCount = it.intentionsAnsweredCount + 1) }
            diagnostics.log("Intention for $packageName: $kind${note?.let { " ($it)" } ?: ""}")
        }
    }

    /**
     * Arms a one-shot delayed check so the time-based half of the threshold fires on its own
     * schedule, independent of whether any scroll event ever arrives. Delays until whichever
     * comes first: the app's configured time threshold, or an active grace-period deadline.
     */
    private fun scheduleThresholdCheck(packageName: String) {
        pendingThresholdCheckJob?.cancel()
        val config = monitoredApps[packageName] ?: return
        val now = System.currentTimeMillis()
        val grace = graceUntilMillis[packageName]
        val timeLimitMillis = config.timeThresholdMinutes * 60_000L
        val delayMillis = if (grace != null) (grace - now).coerceAtLeast(0) else timeLimitMillis

        pendingThresholdCheckJob = serviceScope.launch {
            delay(delayMillis)
            if (currentForegroundPackage == packageName && !isOverlayShowing) {
                Log.d(TAG, "Scheduled threshold check firing for $packageName")
                checkThresholdAndMaybeShowOverlay(packageName)
            }
        }
    }

    private suspend fun checkThresholdAndMaybeShowOverlay(packageName: String) {
        val session = scrollStatsRepository.getActiveSession(packageName) ?: return
        val config = monitoredApps[packageName] ?: return
        val now = System.currentTimeMillis()

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

    private suspend fun showOverlay(app: MonitoredAppEntity, scrollCount: Int, sessionElapsedMillis: Long) {
        val now = System.currentTimeMillis()
        // Two of our windows on screen at once would be absurd, and the prompt asks about an
        // intention this session has by definition already moved past.
        intentionPromptController.hide()
        currentIntentionId = null
        isOverlayShowing = true
        pendingThresholdCheckJob?.cancel()

        val shown = overlayController.show(
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

        if (!shown) {
            // Critical: clear the flag. Leaving it set after a window that never appeared makes
            // handleScroll() and every scheduled threshold check return early from here on, so a
            // single failed overlay would silently stop all scroll counting until the service
            // restarted - a much worse symptom than the missing overlay itself.
            isOverlayShowing = false
            // The controller has already recorded the reason itself - it owns the window, so it
            // owns the facts about it. What it cannot know is which app this was for.
            val reason = overlayController.lastFailureReason
            diagnostics.log("Overlay FAILED to display for ${app.packageName}: $reason")
            Log.e(TAG, "Overlay failed to display for ${app.packageName}: $reason")
            return
        }

        currentOverlayEventId = scrollStatsRepository.recordOverlayShown(
            packageName = app.packageName,
            nowMillis = now,
            scrollCountAtTrigger = scrollCount,
            sessionTimeMillisAtTrigger = sessionElapsedMillis,
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
            if (currentForegroundPackage == packageName) {
                scheduleThresholdCheck(packageName)
            }
        }
    }

    override fun onInterrupt() {
        overlayController.hide()
        intentionPromptController.hide()
        isOverlayShowing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayController.detach()
        intentionPromptController.detach()
        pendingThresholdCheckJob?.cancel()
        serviceJob?.cancel()
        diagnostics.update { it.copy(serviceConnectedAtMillis = null) }
        diagnostics.log("Service destroyed")
    }

    private companion object {
        const val TAG = "MindfulScroll"

        /** Treat scroll events for the same app within this window as one logical swipe. */
        const val SCROLL_DEBOUNCE_MILLIS = 300L

        /**
         * Suppresses a second prompt for the same app this soon after the last one. Aimed at
         * app-switching (checking a message and coming straight back), not at real re-opens - long
         * enough that flicking between two apps doesn't prompt on every hop, short enough that
         * genuinely returning later still asks.
         */
        const val INTENTION_PROMPT_DEBOUNCE_MILLIS = 2 * 60 * 1000L
    }
}
