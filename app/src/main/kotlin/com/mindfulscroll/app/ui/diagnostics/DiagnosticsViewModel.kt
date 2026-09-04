package com.mindfulscroll.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import com.mindfulscroll.app.accessibility.ServiceDiagnostics
import com.mindfulscroll.app.accessibility.ServiceDiagnosticsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    diagnostics: ServiceDiagnostics,
) : ViewModel() {
    val state: StateFlow<ServiceDiagnosticsState> = diagnostics.state
}
