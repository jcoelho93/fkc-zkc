package com.mindfulscroll.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.data.entity.OverlayChoice
import com.mindfulscroll.app.data.entity.PauseOutcome
import com.mindfulscroll.app.data.repository.IntentionRepository
import com.mindfulscroll.app.data.repository.MonitoredAppRepository
import com.mindfulscroll.app.data.repository.ScrollStatsRepository
import com.mindfulscroll.app.grayscale.GrayscaleController
import com.mindfulscroll.app.intention.IntentionPromptController
import com.mindfulscroll.app.overlay.OVERLAY_GRACE_MINUTES
import com.mindfulscroll.app.overlay.OverlayController
import com.mindfulscroll.app.overlay.OverlayUiState
import com.mindfulscroll.app.stats.ScrollGestureCoalescer
import com.mindfulscroll.app.stats.SessionState
import com.mindfulscroll.app.stats.ThresholdConfig
import com.mindfulscroll.app.stats.ThresholdEvaluator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
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

    @Inject lateinit var grayscaleController: GrayscaleController

    private var serviceJob: Job? = null
    private lateinit var serviceScope: CoroutineScope

    /** packageName -> latest known config, refreshed reactively from Room. */
    private var monitoredApps: Map<String, MonitoredAppEntity> = emptyMap()

    private var currentForegroundPackage: String? = null
    private var lastForegroundChangeAtMillis: Long = System.currentTimeMillis()

    /**
     * When the last scroll-related event from the foreground app arrived, on the event's own
     * monotonic clock (AccessibilityEvent.getEventTime, uptime millis), or null when there has been
     * none since it came forward. Every event updates it, counted or not, which is what makes one
     * fling one swipe - see ScrollGestureCoalescer.
     */
    private var lastScrollEventAtUptimeMillis: Long? = null
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

        // Anything left from a previous run is undone before anything else: if the service died
        // with a monitored app in front, the whole screen is still gray and nothing else will fix
        // it. The first foreground event re-applies it if that app is still in front.
        grayscaleController.restoreIfApplied("service connected")

        diagnostics.log("Service connected")
        diagnostics.log("Resolved serviceInfo: $resolved")
        Log.d(TAG, "onServiceConnected - resolved serviceInfo: $resolved")

        monitoredAppRepository.observeMonitored()
            .onEach { apps ->
                monitoredApps = apps.associateBy { it.packageName }
                diagnostics.update { it.copy(monitoredPackages = monitoredApps.keys) }
                diagnostics.log("Monitored apps updated: ${monitoredApps.keys}")
                Log.d(TAG, "Monitored apps: ${monitoredApps.keys}")
                // A grayscale switch flipped (or an app removed) while that app is in front takes
                // effect now, rather than at the next app switch.
                updateGrayscale(currentForegroundPackage)
            }
            .launchIn(serviceScope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Counted before the `when` on purpose: "never called" and "called, then filtered out"
        // are indistinguishable in every other counter, and they have opposite fixes.
        diagnostics.update { it.copy(totalEventCount = it.totalEventCount + 1) }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // The keyboard is a window of its own and announces itself under the keyboard's
                // package, but the user has not left the app they are typing into - see
                // ForegroundTransitions. Counted, so "the keyboard came up" is visible on the
                // Diagnostics screen instead of silently vanishing.
                if (ForegroundTransitions.isKeyboardWindow(event.className?.toString())) {
                    diagnostics.update { it.copy(keyboardWindowEventsIgnored = it.keyboardWindowEventsIgnored + 1) }
                    diagnostics.log("Keyboard window (${event.packageName}) ignored - not a foreground change")
                    return
                }
                handleWindowStateChanged(event.packageName?.toString())
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                diagnostics.update { it.copy(rawScrollEventCount = it.rawScrollEventCount + 1) }
                handleScroll(event.packageName?.toString(), ScrollEventSource.VIEW_SCROLLED, event.eventTime)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                diagnostics.update { it.copy(rawContentChangedEventCount = it.rawContentChangedEventCount + 1) }
                handleScroll(event.packageName?.toString(), ScrollEventSource.WINDOW_CONTENT_CHANGED, event.eventTime)
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
        lastScrollEventAtUptimeMillis = null
        pendingThresholdCheckJob?.cancel()
        diagnostics.update { it.copy(currentForegroundPackage = packageName) }
        diagnostics.log("Foreground -> $packageName")

        // After both guards above, so neither our own windows nor the keyboard toggle grayscale.
        updateGrayscale(packageName)

        serviceScope.launch {
            if (previousPackage != null && isMonitored(previousPackage)) {
                scrollStatsRepository.addForegroundTime(previousPackage, now - previousStart, now)
                scrollStatsRepository.clearSession(previousPackage)
                diagnostics.update { it.copy(activeSessionPackage = null, activeSessionScrollCount = 0, activeSessionStartMillis = null) }
            }
            if (isMonitored(packageName)) {
                scrollStatsRepository.startSession(packageName, now)
                // Here and only here: this is the one transition INTO the app. Scrolls, a session
                // restarted by "5 more minutes", and the keyboard (set aside before this function)
                // are not opens (#28).
                scrollStatsRepository.recordOpen(packageName, now)
                diagnostics.update { it.copy(monitoredAppOpensCounted = it.monitoredAppOpensCounted + 1) }
                diagnostics.update {
                    it.copy(activeSessionPackage = packageName, activeSessionScrollCount = 0, activeSessionStartMillis = now)
                }
                monitoredApps[packageName]?.let { maybeShowIntentionPrompt(it, sessionStartMillis = now) }
                scheduleThresholdCheck(packageName)
            }
        }
    }

    private fun updateGrayscale(foregroundPackage: String?) {
        val wanted = foregroundPackage != null &&
            isMonitored(foregroundPackage) &&
            monitoredApps[foregroundPackage]?.grayscaleEnabled == true
        grayscaleController.onForeground(foregroundPackage, wantGrayscale = wanted)
    }

    private fun handleScroll(packageName: String?, source: ScrollEventSource, eventAtUptimeMillis: Long) {
        if (packageName.isNullOrEmpty()) return
        if (packageName != currentForegroundPackage) return
        if (!isMonitored(packageName)) return
        if (isOverlayShowing) return

        val startsSwipe = ScrollGestureCoalescer.startsNewGesture(lastScrollEventAtUptimeMillis, eventAtUptimeMillis)
        lastScrollEventAtUptimeMillis = eventAtUptimeMillis
        if (!startsSwipe) {
            // Counted rather than just dropped. Raw events far outnumbering ticks is now the
            // expected shape, and this is the number that says the gap is swallowing them.
            diagnostics.update { it.copy(scrollEventsFoldedIntoSwipe = it.scrollEventsFoldedIntoSwipe + 1) }
            return
        }

        val now = System.currentTimeMillis()
        // Split by the event type that opened the swipe, so the Diagnostics screen can show which
        // signal is actually driving the count instead of only a merged total (#25).
        diagnostics.update {
            when (source) {
                ScrollEventSource.VIEW_SCROLLED -> it.copy(
                    countedScrollTicks = it.countedScrollTicks + 1,
                    countedScrollTicksViaViewScrolled = it.countedScrollTicksViaViewScrolled + 1,
                )
                ScrollEventSource.WINDOW_CONTENT_CHANGED -> it.copy(
                    countedScrollTicks = it.countedScrollTicks + 1,
                    countedScrollTicksViaContentChanged = it.countedScrollTicksViaContentChanged + 1,
                )
            }
        }

        serviceScope.launch {
            val session = scrollStatsRepository.recordScroll(packageName, now)
            diagnostics.update {
                it.copy(activeSessionPackage = packageName, activeSessionScrollCount = session.scrollCountInSession)
            }
            diagnostics.log("Scroll #${session.scrollCountInSession} in $packageName (swipe opened by ${source.eventName})")
            Log.d(TAG, "Scroll #${session.scrollCountInSession} in $packageName, swipe opened by ${source.eventName}")

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
                // Counted, not just logged: this is the only path that can cross a time threshold
                // in an app that fires no scroll events, and "it never ran" looks exactly like
                // "it ran and the threshold wasn't crossed yet" in every other counter.
                diagnostics.update { it.copy(scheduledThresholdChecksFired = it.scheduledThresholdChecksFired + 1) }
                diagnostics.log("Scheduled threshold check firing for $packageName")
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
            showOverlay(
                app = config,
                scrollCount = session.scrollCountInSession,
                sessionElapsedMillis = now - session.sessionStartMillis,
                sessionStartMillis = session.sessionStartMillis,
            )
        }
    }

    /**
     * Cancels the armed threshold timer - unless the caller *is* that timer.
     *
     * The guard is load-bearing, and its absence was a silent failure of the exact shape this
     * codebase keeps producing. showOverlay() runs inline inside the scheduled check's own
     * coroutine, so an unguarded cancel here cancels the coroutine that is currently executing.
     * addView() is not a suspension point, so the overlay still draws and every counter still
     * reads as success - and then the first suspending line after it silently never runs,
     * including recordOverlayShown(). The user sees the pause screen; the database has no record
     * that it was ever shown, and no choice can be filed against it afterwards.
     *
     * It only ever affected the time-triggered path (the scroll handler launches its own
     * coroutine, so it cancels a different job), which is the half that Compose feeds depend on
     * entirely - and the half nothing exercised until
     * ThresholdOverlayEndToEndInstrumentedTest.
     */
    private suspend fun cancelPendingThresholdCheckUnlessItIsUs() {
        val runningHere = currentCoroutineContext()[Job]
        pendingThresholdCheckJob?.takeUnless { it === runningHere }?.cancel()
        pendingThresholdCheckJob = null
    }

    private suspend fun showOverlay(
        app: MonitoredAppEntity,
        scrollCount: Int,
        sessionElapsedMillis: Long,
        sessionStartMillis: Long,
    ) {
        val now = System.currentTimeMillis()
        // Two of our windows on screen at once would be absurd, and the prompt asks about an
        // intention this session has by definition already moved past.
        intentionPromptController.hide()
        currentIntentionId = null
        isOverlayShowing = true
        cancelPendingThresholdCheckUnlessItIsUs()

        // The intention for THIS visit, matched on the session's own start time - not the most
        // recent one for the app, which could be from an hour ago. Null is an ordinary case, not
        // an error: capture may be switched off, or the prompt debounced away, and the pause has
        // to work without it. Nothing here fails if it is missing; the screen just does not ask.
        val intention = intentionRepository.getForSession(app.packageName, sessionStartMillis)
        if (intention == null) {
            diagnostics.log("No intention recorded for this ${app.packageName} session - pause will skip the recall")
        }

        val shown = overlayController.show(
            state = OverlayUiState(
                appLabel = app.appLabel,
                scrollCount = scrollCount,
                sessionMinutes = (sessionElapsedMillis / 60_000L).toInt(),
                intentionKind = intention?.kind,
                intentionNote = intention?.note,
                pauseDurationSeconds = appSettings.pauseDurationSecondsNow(),
            ),
            onCloseApp = {
                serviceScope.launch { resolveOverlay(app.packageName, OverlayChoice.CLOSE_APP) }
                performGlobalAction(GLOBAL_ACTION_HOME)
            },
            onContinue = {
                serviceScope.launch { resolveOverlay(app.packageName, OverlayChoice.CONTINUE) }
            },
            onOutcome = { outcome -> recordOutcome(app.packageName, outcome) },
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
            intentionId = intention?.id,
            intentionKind = intention?.kind,
        )
    }

    /**
     * Written as soon as the chip is tapped, without waiting for the user to then pick an exit.
     * "Not really" followed by walking away is a perfectly ordinary thing to do, and it is also
     * one of the most informative rows the weekly report can have - losing it because no exit was
     * ever chosen would bias the outcome data towards the pauses people finished tidily.
     */
    private fun recordOutcome(packageName: String, outcome: PauseOutcome) {
        val eventId = currentOverlayEventId
        if (eventId == null) {
            diagnostics.log("Outcome $outcome for $packageName arrived with no overlay event row - dropped")
            return
        }
        serviceScope.launch {
            scrollStatsRepository.recordOverlayOutcome(eventId, outcome)
            diagnostics.update { it.copy(pauseOutcomesAnsweredCount = it.pauseOutcomesAnsweredCount + 1) }
            diagnostics.log("Pause outcome for $packageName: $outcome")
        }
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
        grayscaleController.restoreIfApplied("service interrupted")
    }

    /** Turning the service off in Settings arrives here; the screen must not stay gray after it. */
    override fun onUnbind(intent: Intent?): Boolean {
        grayscaleController.restoreIfApplied("service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        grayscaleController.restoreIfApplied("service destroyed")
        overlayController.detach()
        intentionPromptController.detach()
        pendingThresholdCheckJob?.cancel()
        serviceJob?.cancel()
        diagnostics.update { it.copy(serviceConnectedAtMillis = null) }
        diagnostics.log("Service destroyed")
    }

    private companion object {
        const val TAG = "MindfulScroll"

        /**
         * Suppresses a second prompt for the same app this soon after the last one. Aimed at
         * app-switching (checking a message and coming straight back), not at real re-opens - long
         * enough that flicking between two apps doesn't prompt on every hop, short enough that
         * genuinely returning later still asks.
         */
        const val INTENTION_PROMPT_DEBOUNCE_MILLIS = 2 * 60 * 1000L
    }
}

/** Which accessibility event type a scroll signal arrived as - kept apart for Diagnostics (#25). */
private enum class ScrollEventSource(val eventName: String) {
    VIEW_SCROLLED("TYPE_VIEW_SCROLLED"),
    WINDOW_CONTENT_CHANGED("TYPE_WINDOW_CONTENT_CHANGED"),
}
