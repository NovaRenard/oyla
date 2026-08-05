package kz.oyla.app.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.R
import kz.oyla.app.data.device.DeviceLifecycle
import kz.oyla.app.data.device.normalizeActivationCode
import kz.oyla.app.data.local.DeviceIdentity
import kz.oyla.app.ui.components.OylaLogo
import kz.oyla.app.ui.components.OylaPrimaryButton
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaTextMuted

@Composable
fun ActivationScreen(state: DeviceStartupUiState.Activation, onActivate: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    StartupContainer {
        OylaLogo(modifier = Modifier.fillMaxWidth(0.35f))
        Spacer(Modifier.height(20.dp))
        Text("Подключение планшета", color = OylaNavy, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
        Text(
            "Получите код подключения у администратора центра",
            color = OylaTextMuted,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = normalizeActivationCode(it).take(DeviceLifecycle.ActivationCodeLength) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSubmitting,
            singleLine = true,
            label = { Text("Код подключения") },
            supportingText = { Text(if (code.isNotBlank()) code.chunked(4).joinToString("  ") else "Введите 8 символов") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            isError = state.error != null
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(6.dp))
        OylaPrimaryButton(
            text = if (state.isSubmitting) "Подключаем…" else "Подключить",
            icon = Icons.Outlined.Link,
            iconDescription = "Подключить планшет",
            enabled = code.length == DeviceLifecycle.ActivationCodeLength && !state.isSubmitting,
            onClick = { onActivate(code) },
            modifier = Modifier.fillMaxWidth(),
            textSize = 20.sp
        )
    }
}

@Composable
fun BlockedDeviceScreen(identity: DeviceIdentity, onRetry: () -> Unit) {
    StartupContainer {
        OylaLogo(modifier = Modifier.fillMaxWidth(0.3f))
        Spacer(Modifier.height(20.dp))
        Text("Планшет заблокирован", color = OylaNavy, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Text("Администратор центра ограничил доступ этого устройства. Обратитесь к администратору", color = OylaTextMuted, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        DeviceIdentityLabels(identity)
        OylaPrimaryButton("Проверить снова", Icons.Outlined.Refresh, "Проверить снова", onRetry, modifier = Modifier.fillMaxWidth(), textSize = 20.sp)
    }
}

@Composable
fun OfflineDeviceScreen(identity: DeviceIdentity, onRetry: () -> Unit) {
    StartupContainer {
        OylaLogo(modifier = Modifier.fillMaxWidth(0.3f))
        Spacer(Modifier.height(20.dp))
        Text("Нет соединения", color = OylaNavy, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Text("Не удалось проверить доступ планшета. Проверьте интернет и повторите попытку.", color = OylaTextMuted, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        DeviceIdentityLabels(identity)
        OylaPrimaryButton("Повторить проверку", Icons.Outlined.WifiOff, "Повторить", onRetry, modifier = Modifier.fillMaxWidth(), textSize = 20.sp)
    }
}

@Composable
private fun StartupContainer(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.72f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
private fun DeviceIdentityLabels(identity: DeviceIdentity) {
    Text(identity.centerName, color = OylaNavy, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    Text(identity.deviceName, color = OylaTextMuted, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(6.dp))
}
