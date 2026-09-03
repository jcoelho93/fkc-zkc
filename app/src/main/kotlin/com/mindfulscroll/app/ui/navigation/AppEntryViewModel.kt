package com.mindfulscroll.app.ui.navigation

import androidx.lifecycle.ViewModel
import com.mindfulscroll.app.data.OnboardingPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppEntryViewModel @Inject constructor(
    onboardingPreferences: OnboardingPreferences,
) : ViewModel() {
    val startDestination: String =
        if (onboardingPreferences.hasCompletedAppSelection) Routes.MAIN else Routes.WELCOME
}
