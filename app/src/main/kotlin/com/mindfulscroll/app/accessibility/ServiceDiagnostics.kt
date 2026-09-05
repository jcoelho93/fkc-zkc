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
    /** TYPE_VIEW_SCROLLED events seen from ANY app, before any filtering - proves the OS is delivering them at all. */
    val rawScrollEventCount: Long = 0,
    /** TYPE_WINDOW_CONTENT_CHANGED events seen from ANY app - the fallback signal, see accessibility_service_config.xml. */
    val rawContentChangedEventCount: Long = 0,
    /** Scroll ticks actually counted (foreground+monitored+past debounce) - what drives thresholds. */
    val countedScrollTicks: Long = 0,
    val activeSessionPackage: String? = null,
    val activeSessionScrollCount: Int = 0,
    val activeSessionStartMillis: Long? = null,
    val overlaysShownCount: Long = 0,
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
