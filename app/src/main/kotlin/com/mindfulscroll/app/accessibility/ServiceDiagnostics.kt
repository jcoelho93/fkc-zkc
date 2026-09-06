package com.mindfulscroll.app.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ServiceDiagnosticsState(
    val serviceConnectedAtMillis: Long? = null,
    val monitoredPackages: Set<String> = emptySet(),
    val currentForegroundPackage: String? = null,
    /**
     * EVERY event delivered to onAccessibilityEvent, of any type, counted before the type
     * `when` and before any filtering. Distinguishes "the OS never called us at all" from
     * "it called us and our own filtering discarded everything" - the two have identical
     * symptoms in every other counter here.
     */
    val totalEventCount: Long = 0,
    /**
     * What the service's own getServiceInfo() reported at connect time: the event-type mask,
     * feedback type and flags as the *system* parsed accessibility_service_config.xml, not as
     * the XML reads. A service can be bound and connected while the system holds an event mask
     * that excludes everything, in which case no event is ever dispatched to it.
     */
    val resolvedServiceInfo: String? = null,
    /**
     * The event-type mask the system resolved for us, as an int so it can be asserted on.
     * 0 means the config XML never reached the system - see the meta-data comment in
     * AndroidManifest.xml. A 0 mask means no event is ever dispatched to the service.
     */
    val resolvedEventTypes: Int = 0,
    /** TYPE_VIEW_SCROLLED events seen from ANY app, before any filtering - proves the OS is delivering them at all. */
    val rawScrollEventCount: Long = 0,
    /** TYPE_WINDOW_CONTENT_CHANGED events seen from ANY app - the fallback signal, see accessibility_service_config.xml. */
    val rawContentChangedEventCount: Long = 0,
    /** Scroll ticks actually counted (foreground+monitored+past debounce) - what drives thresholds. */
    val countedScrollTicks: Long = 0,
    val activeSessionPackage: String? = null,
    val activeSessionScrollCount: Int = 0,
    val activeSessionStartMillis: Long? = null,
    /** Overlay windows that WindowManager accepted - not merely attempted, and not necessarily seen. */
    val overlaysShownCount: Long = 0,
    /**
     * Overlay windows that went on to draw a real, non-zero-sized frame. This is the number
     * that means "the user saw something"; [overlaysShownCount] only means addView() returned
     * without throwing. When these two diverge, the window is being created and then lost
     * somewhere between WindowManager and the screen - a failure that reads as success in
     * every other counter here.
     */
    val overlaysRenderedCount: Long = 0,
    /** When the last overlay window was handed to WindowManager, for pairing with [lastOverlayRender]. */
    val lastOverlayAddedAtMillis: Long? = null,
    /**
     * What the last overlay window actually did after being added: its first-draw size and
     * latency, the render watchdog's complaint that no frame ever arrived, or null while an
     * attempt is still in flight.
     */
    val lastOverlayRender: String? = null,
    /**
     * Why the last overlay attempt failed, or null if the last one worked. An overlay that fails
     * to display looks exactly like a threshold that never fired, so the reason is recorded
     * rather than left to be inferred.
     */
    val lastOverlayError: String? = null,
    /**
     * The intention prompt's own counters, kept separate from the overlay's rather than merged.
     * The two windows fail for different reasons and at wildly different rates - the prompt fires
     * on every app open, the overlay only at a threshold - so a single pair of counters would
     * average away exactly the signal each one is here to give.
     */
    val intentionPromptsShownCount: Long = 0,
    val intentionPromptsRenderedCount: Long = 0,
    val lastIntentionPromptAddedAtMillis: Long? = null,
    val lastIntentionPromptRender: String? = null,
    val lastIntentionPromptError: String? = null,
    /** Prompts the user actually answered, as opposed to ignored - the two are both worth knowing. */
    val intentionsAnsweredCount: Long = 0,
    /** Newest first, capped - enough to see what just happened without adb. */
    val recentLog: List<String> = emptyList(),
) {
    val isServiceConnected: Boolean get() = serviceConnectedAtMillis != null
}

/**
 * Live, in-memory (never persisted) view into what ScrollMonitorService is doing right now.
 * Both the service and the Diagnostics screen run in the same process (no android:process
 * override on the service), so a plain injected singleton StateFlow is enough - no IPC needed.
 */
@Singleton
class ServiceDiagnostics @Inject constructor() {
    private val _state = MutableStateFlow(ServiceDiagnosticsState())
    val state: StateFlow<ServiceDiagnosticsState> = _state.asStateFlow()

    fun update(transform: (ServiceDiagnosticsState) -> ServiceDiagnosticsState) {
        _state.value = transform(_state.value)
    }

    fun log(line: String) {
        _state.value = _state.value.copy(
            recentLog = (listOf(line) + _state.value.recentLog).take(MAX_LOG_LINES),
        )
    }

    private companion object {
        const val MAX_LOG_LINES = 60
    }
}
