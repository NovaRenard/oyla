package kz.oyla.app.ui.specialist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.R
import kz.oyla.app.domain.model.DeviceRole
import kz.oyla.app.ui.components.OylaLogo
import kz.oyla.app.ui.components.OylaPrimaryButton
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaTextMuted

@Composable
fun SpecialistSettingsScreen(
    role: DeviceRole,
    onBack: () -> Unit,
    onChangeMode: () -> Unit
) {
    BackHandler(onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.content_description_back),
                tint = OylaNavy
            )
        }
        OylaLogo(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(0.16f)
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 600.dp)
                .fillMaxWidth(0.64f)
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                color = OylaNavy,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.current_mode),
                color = OylaTextMuted,
                fontSize = 20.sp
            )
            Text(
                text = stringResource(
                    R.string.current_mode_value,
                    if (role == DeviceRole.SPECIALIST) {
                        stringResource(R.string.role_specialist)
                    } else {
                        stringResource(R.string.role_child)
                    }
                ),
                color = OylaNavy,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
            OylaPrimaryButton(
                text = stringResource(R.string.change_device_mode),
                icon = Icons.Outlined.SwapHoriz,
                iconDescription = stringResource(R.string.change_device_mode),
                onClick = onChangeMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            )
        }
    }
}
