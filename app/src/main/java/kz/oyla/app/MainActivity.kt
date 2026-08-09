package kz.oyla.app

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kz.oyla.app.data.local.DevicePreferences
import kz.oyla.app.data.device.DeviceRuntimeInfo
import kz.oyla.app.data.local.DeviceIdentity
import kz.oyla.app.data.remote.DeviceAuthApiClient
import kz.oyla.app.data.remote.OylaApiClient
import kz.oyla.app.data.remote.OylaWebSocketClient
import kz.oyla.app.data.session.SessionRepository
import kz.oyla.app.domain.model.DeviceRole
import kz.oyla.app.navigation.OylaDestination
import kz.oyla.app.navigation.OylaNavGraph
import kz.oyla.app.ui.device.ActivationScreen
import kz.oyla.app.ui.device.BlockedDeviceScreen
import kz.oyla.app.ui.device.DeviceStartupUiState
import kz.oyla.app.ui.device.DeviceStartupViewModel
import kz.oyla.app.ui.device.DeviceStartupViewModelFactory
import kz.oyla.app.ui.device.OfflineDeviceScreen
import kz.oyla.app.ui.theme.OylaTheme
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideNavigationBar()
        setContent {
            OylaTheme {
                val devicePreferences = remember { DevicePreferences(applicationContext) }
                OylaApp(devicePreferences)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    private fun hideNavigationBar() {
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
}

@Composable
private fun OylaApp(devicePreferences: DevicePreferences) {
    val gateway = remember { DeviceAuthApiClient(BuildConfig.API_BASE_URL) }
    val runtimeInfo = remember {
        DeviceRuntimeInfo(
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        )
    }
    val startupViewModel: DeviceStartupViewModel = viewModel(
        factory = remember { DeviceStartupViewModelFactory(devicePreferences, gateway, runtimeInfo) }
    )
    val state by startupViewModel.uiState.collectAsState()
    when (state) {
        DeviceStartupUiState.Loading -> LoadingScreen()
        is DeviceStartupUiState.Activation -> ActivationScreen(state as DeviceStartupUiState.Activation, startupViewModel::activate)
        is DeviceStartupUiState.Blocked -> BlockedDeviceScreen((state as DeviceStartupUiState.Blocked).identity, startupViewModel::validate)
        is DeviceStartupUiState.Offline -> OfflineDeviceScreen((state as DeviceStartupUiState.Offline).identity, startupViewModel::validate)
        is DeviceStartupUiState.Ready -> ActivatedOylaApp((state as DeviceStartupUiState.Ready).identity, devicePreferences, gateway)
    }
}

@Composable
private fun ActivatedOylaApp(identity: DeviceIdentity, devicePreferences: DevicePreferences, gateway: DeviceAuthApiClient) {
    var startDestination by remember(identity.deviceId) { mutableStateOf<OylaDestination?>(null) }
    LaunchedEffect(identity.deviceId, identity.deviceRole) {
        startDestination = when {
            identity.deviceRole == DeviceRole.CHILD -> OylaDestination.CHILD_IDLE
            else -> OylaDestination.SPECIALIST_HOME
        }
    }
    val destination = startDestination ?: return LoadingScreen()
    val apiClient = remember { OylaApiClient(BuildConfig.API_BASE_URL) }
    val webSocketClient = remember { OylaWebSocketClient(BuildConfig.WS_BASE_URL) }
    val sessionRepository = remember { SessionRepository(apiClient, devicePreferences) }
    OylaNavGraph(
        devicePreferences = devicePreferences,
        startDestination = destination,
        sessionRepository = sessionRepository,
        webSocketClient = webSocketClient,
        deviceGateway = gateway,
        deviceToken = identity.deviceToken,
        centerName = identity.centerName,
        deviceName = identity.deviceName,
        allowLegacyRoleSelection = BuildConfig.DEBUG
    )
}

@Composable
private fun LoadingScreen() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.width(260.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.oyla_startup_logo),
                contentDescription = stringResource(R.string.content_description_logo),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 200.dp, height = 180.dp)
            )
            Spacer(modifier = Modifier.height(28.dp))
            LinearProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                modifier = Modifier
                    .width(220.dp)
                    .height(6.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.loading),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
