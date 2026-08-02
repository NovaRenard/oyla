package kz.oyla.app.ui.child

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kz.oyla.app.ui.components.OylaPrimaryButton
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaOutline
import kz.oyla.app.ui.theme.OylaTextMuted

@Composable
fun ChildConnectScreen(
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var code by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val codeDescription = stringResource(R.string.content_description_code_input)

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
                    .padding(top = contentTopPadding)
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
                    onCodeChange = { code = it }
                )
                OylaPrimaryButton(
                    text = stringResource(R.string.connect),
                    icon = Icons.Outlined.Link,
                    iconDescription = stringResource(R.string.content_description_connect),
                    enabled = code.length == 4,
                    onClick = onConnect,
                    textSize = 25.sp,
                    minHeight = 88.dp,
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .padding(top = 16.dp)
                )
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
    onCodeChange: (String) -> Unit
) {
    val cellShape = RoundedCornerShape(22.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(174.dp)
            .clickable {
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
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            keyboardActions = KeyboardActions(onDone = { keyboardController() }),
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
