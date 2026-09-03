package com.mindfulscroll.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mindfulscroll.app.ui.appselection.AppSelectionScreen
import com.mindfulscroll.app.ui.home.MainScreen
import com.mindfulscroll.app.ui.onboarding.PermissionScreen
import com.mindfulscroll.app.ui.onboarding.WelcomeScreen

@Composable
fun MindfulScrollNavHost(navController: NavHostController = rememberNavController()) {
    val entryViewModel: AppEntryViewModel = hiltViewModel()

    NavHost(navController = navController, startDestination = entryViewModel.startDestination) {
        composable(Routes.WELCOME) {
            WelcomeScreen(onGetStarted = { navController.navigate(Routes.PERMISSIONS) })
        }
        composable(Routes.PERMISSIONS) {
            PermissionScreen(
                onContinue = {
                    navController.navigate(Routes.APP_SELECTION) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.APP_SELECTION) {
            AppSelectionScreen(
                onDone = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.MAIN) {
            MainScreen()
        }
    }
}
