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
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.oyla.app.R
import kz.oyla.app.data.local.PinVerificationResult
import kz.oyla.app.ui.components.OylaPrimaryButton
import kz.oyla.app.ui.theme.OylaNavy

@Composable
fun VerifyPinScreen(
    getRemainingLockMillis: suspend () -> Long,
    verifyPin: suspend (String) -> PinVerificationResult,
    onVerified: suspend () -> Unit
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var showIncorrect by rememberSaveable { mutableStateOf(false) }
    var remainingLockMillis by rememberSaveable { mutableLongStateOf(0L) }
    var isVerifying by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val locked = remainingLockMillis > 0L

    LaunchedEffect(Unit) {
        remainingLockMillis = getRemainingLockMillis()
    }
    LaunchedEffect(locked) {
        while (remainingLockMillis > 0L) {
            delay(250L)
            remainingLockMillis = getRemainingLockMillis()
        }
    }

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
                .widthIn(max = 500.dp)
                .fillMaxWidth(0.62f)
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.verify_pin_title),
                color = OylaNavy,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.verify_pin_subtitle),
                color = OylaNavy.copy(alpha = 0.72f),
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )
            PinField(
                value = pin,
                label = stringResource(R.string.pin_first_label),
                description = stringResource(R.string.content_description_pin_input),
                isError = showIncorrect,
                onValueChange = {
                    pin = it
                    showIncorrect = false
                }
            )
            when {
                locked -> Text(
                    text = stringResource(
                        R.string.pin_locked,
                        ceil(remainingLockMillis / 1000.0).toInt()
                    ),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )

                showIncorrect -> Text(
                    text = stringResource(R.string.incorrect_pin),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 16.sp
                )
            }
            OylaPrimaryButton(
                text = stringResource(R.string.verify_action),
                icon = Icons.Outlined.LockOpen,
                iconDescription = stringResource(R.string.verify_action),
                enabled = pin.length == 4 && !locked && !isVerifying,
                onClick = {
                    scope.launch {
                        isVerifying = true
                        when (val result = verifyPin(pin)) {
                            PinVerificationResult.Success -> onVerified()
                            PinVerificationResult.Incorrect -> showIncorrect = true
                            is PinVerificationResult.Locked -> {
                                remainingLockMillis = result.remainingMillis
                                showIncorrect = false
                            }
                        }
                        isVerifying = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}
