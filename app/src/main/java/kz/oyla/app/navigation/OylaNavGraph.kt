package kz.oyla.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kz.oyla.app.data.remote.DeviceAuthGateway
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
import kz.oyla.app.ui.session.SpecialistExerciseScreen
import kz.oyla.app.ui.session.ChildExerciseScreen
import kz.oyla.app.ui.session.SpecialistSummaryScreen
import kz.oyla.app.ui.lesson.ChildAssignmentViewModel
import kz.oyla.app.ui.lesson.ChildAssignmentViewModelFactory
import kz.oyla.app.ui.lesson.ChildDeviceSelectionScreen
import kz.oyla.app.ui.lesson.ChildIdleScreen
import kz.oyla.app.ui.lesson.ChildSelectionScreen
import kz.oyla.app.ui.lesson.LessonConfirmationScreen
import kz.oyla.app.ui.lesson.ManagedLessonLaunchViewModel
import kz.oyla.app.ui.lesson.ManagedLessonLaunchViewModelFactory
import kz.oyla.app.ui.lesson.SpecialistSelectionScreen

@Composable
fun OylaNavGraph(
    devicePreferences: DevicePreferences,
    startDestination: OylaDestination,
    sessionRepository: SessionRepository,
    webSocketClient: OylaWebSocketClient,
    deviceGateway: DeviceAuthGateway,
    deviceToken: String,
    centerName: String,
    deviceName: String,
    allowLegacyRoleSelection: Boolean = false
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val specialistViewModel: SpecialistSessionViewModel = viewModel(
        factory = remember { SpecialistSessionViewModelFactory(sessionRepository, webSocketClient) }
    )
    val childViewModel: ChildSessionViewModel = viewModel(
        factory = remember { ChildSessionViewModelFactory(sessionRepository, webSocketClient) }
    )
    val lessonLaunchViewModel: ManagedLessonLaunchViewModel = viewModel(
        factory = remember { ManagedLessonLaunchViewModelFactory(deviceGateway, deviceToken, sessionRepository, devicePreferences) }
    )
    val childAssignmentViewModel: ChildAssignmentViewModel = viewModel(
        factory = remember { ChildAssignmentViewModelFactory(deviceGateway, deviceToken, sessionRepository) }
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
            composable(OylaDestination.CHANGE_PIN.route) {
                CreatePinScreen { pin ->
                    devicePreferences.savePin(pin)
                    navController.navigate(OylaDestination.SPECIALIST_SETTINGS.route) {
                        popUpTo(OylaDestination.CHANGE_PIN.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            composable(OylaDestination.SPECIALIST_HOME.route) {
                val lessonState by lessonLaunchViewModel.uiState.collectAsState()
                LaunchedEffect(Unit) { lessonLaunchViewModel.restoreCurrentLesson() }
                LaunchedEffect(lessonState.recoveredLesson?.sessionId) {
                    lessonState.recoveredLesson?.let { lesson ->
                        lessonLaunchViewModel.consumeRecoveredLesson()
                        specialistViewModel.startManagedLesson(lesson)
                        navController.navigate(OylaDestination.SPECIALIST_WAITING.route) { launchSingleTop = true }
                    }
                }
                SpecialistHomeScreen(
                    onNewLesson = {
                        navController.navigate(OylaDestination.SELECT_SPECIALIST.route)
                    },
                    centerName = centerName,
                    deviceName = deviceName,
                    onOpenSettings = {
                        navController.navigate(OylaDestination.SPECIALIST_SETTINGS.route)
                    }
                )
            }
            composable(OylaDestination.SPECIALIST_SETTINGS.route) {
                SpecialistSettingsScreen(
                    role = DeviceRole.SPECIALIST,
                    onBack = { navController.popBackStack() },
                    onChangeMode = if (allowLegacyRoleSelection) {
                        {
                        navController.navigate(OylaDestination.VERIFY_PIN.route)
                        }
                    } else null,
                    onChangePin = if (allowLegacyRoleSelection) {
                        {
                        navController.navigate(OylaDestination.CHANGE_PIN.route)
                        }
                    } else null
                )
            }
            composable(OylaDestination.CHILD_SETTINGS.route) {
                SpecialistSettingsScreen(
                    role = DeviceRole.CHILD,
                    onBack = { navController.popBackStack() },
                    onChangeMode = if (allowLegacyRoleSelection) {
                        {
                        scope.launch {
                            if (devicePreferences.hasPin()) {
                                navController.navigate(OylaDestination.VERIFY_PIN.route)
                            } else {
                                navController.navigate(OylaDestination.CREATE_PIN.route)
                            }
                        }
                        }
                    } else null
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
            composable(OylaDestination.CHILD_IDLE.route) {
                ChildIdleScreen(childAssignmentViewModel) { assignment ->
                    childViewModel.acceptManagedAssignment(assignment) {
                        navController.navigate(OylaDestination.CHILD_WAITING.route) {
                            popUpTo(OylaDestination.CHILD_IDLE.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
            composable(OylaDestination.SELECT_SPECIALIST.route) {
                SpecialistSelectionScreen(
                    viewModel = lessonLaunchViewModel,
                    onNext = { navController.navigate(OylaDestination.SELECT_CHILD.route) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(OylaDestination.SELECT_CHILD.route) {
                ChildSelectionScreen(
                    viewModel = lessonLaunchViewModel,
                    onNext = { navController.navigate(OylaDestination.SELECT_CHILD_DEVICE.route) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(OylaDestination.SELECT_CHILD_DEVICE.route) {
                ChildDeviceSelectionScreen(
                    viewModel = lessonLaunchViewModel,
                    onNext = { navController.navigate(OylaDestination.CONFIRM_LESSON.route) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(OylaDestination.CONFIRM_LESSON.route) {
                LessonConfirmationScreen(
                    viewModel = lessonLaunchViewModel,
                    onBack = { navController.popBackStack() },
                    onStarted = { lesson ->
                        specialistViewModel.startManagedLesson(lesson)
                        navController.navigate(OylaDestination.SPECIALIST_WAITING.route) {
                            popUpTo(OylaDestination.SPECIALIST_HOME.route) { inclusive = false }
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
                    onOpenExercise = {
                        navController.navigate(OylaDestination.SPECIALIST_EXERCISE.route) { launchSingleTop = true }
                    },
                    onOpenSummary = {
                        navController.navigate(OylaDestination.SPECIALIST_SUMMARY.route) { launchSingleTop = true }
                    },
                    onCancelled = {
                        navController.navigate(OylaDestination.SPECIALIST_HOME.route) {
                            popUpTo(OylaDestination.SPECIALIST_HOME.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(OylaDestination.CHILD_WAITING.route) {
                ChildWaitingScreen(
                    viewModel = childViewModel,
                    onExerciseShown = {
                        navController.navigate(OylaDestination.CHILD_EXERCISE.route) { launchSingleTop = true }
                    },
                    onCancelled = {
                        navController.navigate(OylaDestination.CHILD_IDLE.route) {
                            popUpTo(OylaDestination.CHILD_IDLE.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(OylaDestination.SPECIALIST_EXERCISE.route) {
                SpecialistExerciseScreen(
                    viewModel = specialistViewModel,
                    onCompleted = {
                        navController.navigate(OylaDestination.SPECIALIST_HOME.route) {
                            popUpTo(OylaDestination.SPECIALIST_HOME.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenSummary = {
                        navController.navigate(OylaDestination.SPECIALIST_SUMMARY.route) { launchSingleTop = true }
                    },
                    onOpenSettings = {
                        navController.navigate(OylaDestination.SPECIALIST_SETTINGS.route) { launchSingleTop = true }
                    }
                )
            }
            composable(OylaDestination.SPECIALIST_SUMMARY.route) {
                SpecialistSummaryScreen(
                    viewModel = specialistViewModel,
                    onCompleted = {
                        navController.navigate(OylaDestination.SPECIALIST_HOME.route) {
                            popUpTo(OylaDestination.SPECIALIST_HOME.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(OylaDestination.CHILD_EXERCISE.route) {
                ChildExerciseScreen(
                    viewModel = childViewModel,
                    onOpenSettings = { navController.navigate(OylaDestination.CHILD_SETTINGS.route) },
                    onSessionEnded = {
                        navController.navigate(OylaDestination.CHILD_IDLE.route) {
                            popUpTo(OylaDestination.CHILD_IDLE.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }
}
