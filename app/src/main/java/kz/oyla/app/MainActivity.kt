package kz.oyla.app

import android.os.Bundle
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
    var startDestination by remember { mutableStateOf<OylaDestination?>(null) }

    LaunchedEffect(devicePreferences) {
        val setup = devicePreferences.setupFlow.first()
        val normalStart = setup.toStartDestination()
        val active = devicePreferences.getActiveSession()
        startDestination = when {
            !setup.hasPin || setup.role == null -> normalStart
            active?.role == DeviceRole.SPECIALIST -> OylaDestination.SPECIALIST_WAITING
            active?.role == DeviceRole.CHILD -> OylaDestination.CHILD_WAITING
            else -> normalStart
        }
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
