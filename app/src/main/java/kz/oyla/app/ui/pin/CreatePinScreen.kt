package kz.oyla.app.ui.pin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kz.oyla.app.R
import kz.oyla.app.ui.components.OylaPrimaryButton
import kz.oyla.app.ui.theme.OylaNavy

@Composable
fun CreatePinScreen(onPinCreated: suspend (String) -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var showMismatch by rememberSaveable { mutableStateOf(false) }
    var pinVisible by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .clickable {
                focusManager.clearFocus()
                keyboardController?.hide()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.56f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.create_pin_title),
                color = OylaNavy,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.create_pin_subtitle),
                color = OylaNavy.copy(alpha = 0.72f),
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            PinField(
                value = pin,
                label = stringResource(R.string.pin_first_label),
                pinVisible = pinVisible,
                onVisibilityToggle = { pinVisible = !pinVisible },
                isError = showMismatch,
                onValueChange = {
                    pin = it
                    showMismatch = false
                }
            )
            PinField(
                value = confirmation,
                label = stringResource(R.string.pin_second_label),
                pinVisible = pinVisible,
                onVisibilityToggle = { pinVisible = !pinVisible },
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
                textSize = 22.sp,
                minHeight = 56.dp,
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
    pinVisible: Boolean,
    onVisibilityToggle: () -> Unit,
    isError: Boolean,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue -> onValueChange(newValue.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (pinVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
            color = OylaNavy,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        ),
        trailingIcon = {
            IconButton(onClick = onVisibilityToggle) {
                Icon(
                    imageVector = if (pinVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(
                        if (pinVisible) R.string.hide_pin else R.string.show_pin
                    ),
                    tint = OylaNavy
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
