package kz.oyla.app.ui.child

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.R
import kz.oyla.app.ui.components.OylaBackground
import kz.oyla.app.ui.components.OylaLogo
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaOutline
import kz.oyla.app.ui.theme.OylaTextMuted
import kz.oyla.app.ui.session.ChildSessionViewModel

@Composable
fun ChildConnectScreen(
    viewModel: ChildSessionViewModel,
    onOpenSettings: () -> Unit,
    onConnected: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var code by rememberSaveable { mutableStateOf("") }
    var lastAutoSubmittedCode by rememberSaveable { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val codeDescription = stringResource(R.string.content_description_code_input)
    LaunchedEffect(state.connectionSuccessId) {
        if (state.connectionSuccessId != null) {
            viewModel.consumeConnectionSuccess()
            onConnected()
        }
    }
    LaunchedEffect(code) {
        if (code.length < 4) {
            lastAutoSubmittedCode = null
        } else if (code != lastAutoSubmittedCode && !state.isLoading) {
            lastAutoSubmittedCode = code
            focusManager.clearFocus()
            keyboardController?.hide()
            viewModel.connect(code)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val contentTopPadding = maxHeight * 0.17f
        val codeCellWidth = (maxWidth * 0.095f).coerceIn(80.dp, 120.dp)
        OylaBackground(R.drawable.bg_child_connect)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = maxWidth * 0.04f, vertical = maxHeight * 0.045f)
                .clickable {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
        ) {
            OylaLogo(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.17f)
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(72.dp)
                    .shadow(4.dp, RoundedCornerShape(22.dp))
                    .background(Color.White, RoundedCornerShape(22.dp))
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.content_description_settings),
                    tint = OylaNavy,
                    modifier = Modifier.size(34.dp)
                )
            }
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.60f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(top = contentTopPadding, bottom = 24.dp)
            ) {
                Text(
                    text = stringResource(R.string.child_connect_title),
                    color = OylaNavy,
                    fontSize = 46.sp,
                    lineHeight = 54.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.child_connect_subtitle),
                    color = OylaTextMuted,
                    fontSize = 27.sp,
                    lineHeight = 35.sp,
                    textAlign = TextAlign.Center
                )
                CodeInput(
                    code = code,
                    cellWidth = codeCellWidth,
                    focusRequester = focusRequester,
                    keyboardController = { keyboardController?.show() },
                    contentDescription = codeDescription,
                    enabled = !state.isLoading,
                    onCodeChange = { code = it }
                )
                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center
                    )
                }
                if (state.isLoading) {
                    CircularProgressIndicator(color = OylaNavy)
                }
            }
        }
    }
}

@Composable
private fun CodeInput(
    code: String,
    cellWidth: Dp,
    focusRequester: FocusRequester,
    keyboardController: () -> Unit,
    contentDescription: String,
    enabled: Boolean,
    onCodeChange: (String) -> Unit
) {
    val cellShape = RoundedCornerShape(22.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(174.dp)
            .clickable(enabled = enabled) {
                focusRequester.requestFocus()
                keyboardController()
            }
            .semantics { this.contentDescription = contentDescription }
    ) {
        BasicTextField(
            value = code,
            onValueChange = { newValue ->
                onCodeChange(newValue.filter(Char::isDigit).take(4))
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(4) { index ->
                val digit = code.getOrNull(index)?.toString()
                    ?: stringResource(R.string.code_placeholder)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(cellWidth)
                        .height(144.dp)
                        .background(Color.White.copy(alpha = 0.93f), cellShape)
                        .border(2.dp, OylaOutline, cellShape)
                ) {
                    Text(
                        text = digit,
                        color = OylaNavy,
                        fontSize = if (code.getOrNull(index) == null) 42.sp else 54.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
