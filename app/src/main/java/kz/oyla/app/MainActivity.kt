package kz.oyla.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.first
import kz.oyla.app.data.local.DevicePreferences
import kz.oyla.app.data.local.DeviceSetup
import kz.oyla.app.data.remote.OylaApiClient
import kz.oyla.app.data.remote.OylaWebSocketClient
import kz.oyla.app.data.session.SessionRepository
import kz.oyla.app.domain.model.DeviceRole
import kz.oyla.app.navigation.OylaDestination
import kz.oyla.app.navigation.OylaNavGraph
import kz.oyla.app.ui.theme.OylaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideNavigationBar()
        setContent {
            OylaTheme {
                OylaApp(DevicePreferences(applicationContext))
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    private fun hideNavigationBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
}

@Composable
private fun OylaApp(devicePreferences: DevicePreferences) {
    var startDestination by remember { mutableStateOf<OylaDestination?>(null) }

    LaunchedEffect(devicePreferences) {
        startDestination = devicePreferences.setupFlow.first().toStartDestination()
    }

    val destination = startDestination
    if (destination == null) {
        LoadingScreen()
    } else {
        val apiClient = remember { OylaApiClient(BuildConfig.API_BASE_URL) }
        val webSocketClient = remember { OylaWebSocketClient(BuildConfig.WS_BASE_URL) }
        val sessionRepository = remember { SessionRepository(apiClient, devicePreferences) }
        OylaNavGraph(
            devicePreferences = devicePreferences,
            startDestination = destination,
            sessionRepository = sessionRepository,
            webSocketClient = webSocketClient
        )
    }
}

private fun DeviceSetup.toStartDestination(): OylaDestination = when (role) {
    null -> OylaDestination.ROLE_SELECTION
    else -> if (!hasPin) OylaDestination.CREATE_PIN else when (role) {
        DeviceRole.CHILD -> OylaDestination.CHILD_CONNECT
        DeviceRole.SPECIALIST -> OylaDestination.SPECIALIST_HOME
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        androidx.compose.material3.Text(
            text = stringResource(R.string.loading),
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
