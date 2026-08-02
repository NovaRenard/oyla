package kz.oyla.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kz.oyla.app.R
import kz.oyla.app.data.local.DevicePreferences
import kz.oyla.app.domain.model.DeviceRole
import kz.oyla.app.ui.child.ChildConnectScreen
import kz.oyla.app.ui.onboarding.RoleSelectionScreen
import kz.oyla.app.ui.pin.CreatePinScreen
import kz.oyla.app.ui.pin.VerifyPinScreen
import kz.oyla.app.ui.specialist.SpecialistHomeScreen
import kz.oyla.app.ui.specialist.SpecialistSettingsScreen

@Composable
fun OylaNavGraph(
    devicePreferences: DevicePreferences,
    startDestination: OylaDestination
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val newLessonMessage = context.getString(R.string.new_lesson_coming_soon)
    val connectMessage = context.getString(R.string.connect_coming_soon)

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(OylaDestination.ROLE_SELECTION.route) {
                RoleSelectionScreen { role ->
                    scope.launch {
                        devicePreferences.saveRole(role)
                        val destination = when {
                            role == DeviceRole.CHILD -> OylaDestination.CHILD_CONNECT
                            devicePreferences.hasPin() -> OylaDestination.SPECIALIST_HOME
                            else -> OylaDestination.CREATE_PIN
                        }
                        navController.navigate(destination.route) {
                            popUpTo(OylaDestination.ROLE_SELECTION.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
            composable(OylaDestination.CREATE_PIN.route) {
                CreatePinScreen { pin ->
                    devicePreferences.savePin(pin)
                    navController.navigate(OylaDestination.SPECIALIST_HOME.route) {
                        popUpTo(OylaDestination.ROLE_SELECTION.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            composable(OylaDestination.SPECIALIST_HOME.route) {
                SpecialistHomeScreen(
                    onNewLesson = {
                        scope.launch { snackbarHostState.showSnackbar(newLessonMessage) }
                    },
                    onOpenSettings = {
                        navController.navigate(OylaDestination.SPECIALIST_SETTINGS.route)
                    }
                )
            }
            composable(OylaDestination.SPECIALIST_SETTINGS.route) {
                SpecialistSettingsScreen(
                    role = DeviceRole.SPECIALIST,
                    onBack = { navController.popBackStack() },
                    onChangeMode = {
                        navController.navigate(OylaDestination.VERIFY_PIN.route)
                    }
                )
            }
            composable(OylaDestination.VERIFY_PIN.route) {
                VerifyPinScreen(
                    getRemainingLockMillis = devicePreferences::remainingPinLockMillis,
                    verifyPin = devicePreferences::verifyPin,
                    onVerified = {
                        devicePreferences.clearRole()
                        navController.navigate(OylaDestination.ROLE_SELECTION.route) {
                            popUpTo(OylaDestination.SPECIALIST_HOME.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(OylaDestination.CHILD_CONNECT.route) {
                ChildConnectScreen(
                    onConnect = {
                        scope.launch { snackbarHostState.showSnackbar(connectMessage) }
                    }
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(24.dp)
        )
    }
}
