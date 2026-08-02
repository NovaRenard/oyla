package kz.oyla.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kz.oyla.app.data.local.DevicePreferences
import kz.oyla.app.data.remote.OylaWebSocketClient
import kz.oyla.app.data.session.SessionRepository
import kz.oyla.app.domain.model.DeviceRole
import kz.oyla.app.ui.child.ChildConnectScreen
import kz.oyla.app.ui.onboarding.RoleSelectionScreen
import kz.oyla.app.ui.pin.CreatePinScreen
import kz.oyla.app.ui.pin.VerifyPinScreen
import kz.oyla.app.ui.specialist.SpecialistHomeScreen
import kz.oyla.app.ui.specialist.SpecialistSettingsScreen
import kz.oyla.app.ui.session.ChildSessionViewModel
import kz.oyla.app.ui.session.ChildSessionViewModelFactory
import kz.oyla.app.ui.session.ChildWaitingScreen
import kz.oyla.app.ui.session.CreateSessionScreen
import kz.oyla.app.ui.session.SpecialistSessionViewModel
import kz.oyla.app.ui.session.SpecialistSessionViewModelFactory
import kz.oyla.app.ui.session.SpecialistWaitingScreen

@Composable
fun OylaNavGraph(
    devicePreferences: DevicePreferences,
    startDestination: OylaDestination,
    sessionRepository: SessionRepository,
    webSocketClient: OylaWebSocketClient
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val specialistViewModel: SpecialistSessionViewModel = viewModel(
        factory = remember { SpecialistSessionViewModelFactory(sessionRepository, webSocketClient) }
    )
    val childViewModel: ChildSessionViewModel = viewModel(
        factory = remember { ChildSessionViewModelFactory(sessionRepository, webSocketClient) }
    )

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
                            !devicePreferences.hasPin() -> OylaDestination.CREATE_PIN
                            role == DeviceRole.CHILD -> OylaDestination.CHILD_CONNECT
                            else -> OylaDestination.SPECIALIST_HOME
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
                    val destination = when (devicePreferences.setupFlow.first().role) {
                        DeviceRole.CHILD -> OylaDestination.CHILD_CONNECT
                        else -> OylaDestination.SPECIALIST_HOME
                    }
                    navController.navigate(destination.route) {
                        popUpTo(OylaDestination.ROLE_SELECTION.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            composable(OylaDestination.SPECIALIST_HOME.route) {
                SpecialistHomeScreen(
                    onNewLesson = {
                        navController.navigate(OylaDestination.CREATE_SESSION.route)
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
            composable(OylaDestination.CHILD_SETTINGS.route) {
                SpecialistSettingsScreen(
                    role = DeviceRole.CHILD,
                    onBack = { navController.popBackStack() },
                    onChangeMode = {
                        scope.launch {
                            if (devicePreferences.hasPin()) {
                                navController.navigate(OylaDestination.VERIFY_PIN.route)
                            } else {
                                navController.navigate(OylaDestination.CREATE_PIN.route)
                            }
                        }
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
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(OylaDestination.CHILD_CONNECT.route) {
                ChildConnectScreen(
                    viewModel = childViewModel,
                    onOpenSettings = {
                        navController.navigate(OylaDestination.CHILD_SETTINGS.route)
                    },
                    onConnected = {
                        navController.navigate(OylaDestination.CHILD_WAITING.route) {
                            popUpTo(OylaDestination.CHILD_CONNECT.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(OylaDestination.CREATE_SESSION.route) {
                CreateSessionScreen(
                    viewModel = specialistViewModel,
                    onBack = { navController.popBackStack() },
                    onCreated = {
                        navController.navigate(OylaDestination.SPECIALIST_WAITING.route) {
                            popUpTo(OylaDestination.CREATE_SESSION.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(OylaDestination.SPECIALIST_WAITING.route) {
                SpecialistWaitingScreen(
                    viewModel = specialistViewModel,
                    onCancelled = {
                        navController.navigate(OylaDestination.SPECIALIST_HOME.route) {
                            popUpTo(OylaDestination.SPECIALIST_HOME.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(OylaDestination.CHILD_WAITING.route) {
                ChildWaitingScreen(viewModel = childViewModel)
            }
        }
    }
}
