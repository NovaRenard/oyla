package kz.oyla.app.ui.pin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kz.oyla.app.R
import kz.oyla.app.ui.components.OylaPrimaryButton
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaSurface

@Composable
fun CreatePinScreen(onPinCreated: suspend (String) -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var showMismatch by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pinDescription = stringResource(R.string.content_description_pin_input)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.62f)
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.create_pin_title),
                color = OylaNavy,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.create_pin_subtitle),
                color = OylaNavy.copy(alpha = 0.72f),
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )
            PinField(
                value = pin,
                label = stringResource(R.string.pin_first_label),
                description = pinDescription,
                isError = showMismatch,
                onValueChange = {
                    pin = it
                    showMismatch = false
                }
            )
            PinField(
                value = confirmation,
                label = stringResource(R.string.pin_second_label),
                description = pinDescription,
                isError = showMismatch,
                onValueChange = {
                    confirmation = it
                    showMismatch = false
                }
            )
            if (showMismatch) {
                Text(
                    text = stringResource(R.string.pin_mismatch),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
            OylaPrimaryButton(
                text = stringResource(R.string.continue_action),
                icon = Icons.Outlined.Lock,
                iconDescription = stringResource(R.string.continue_action),
                enabled = pin.length == 4 && confirmation.length == 4,
                onClick = {
                    if (pin == confirmation) {
                        scope.launch { onPinCreated(pin) }
                    } else {
                        showMismatch = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}

@Composable
internal fun PinField(
    value: String,
    label: String,
    description: String,
    isError: Boolean,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue -> onValueChange(newValue.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description }
    )
}
