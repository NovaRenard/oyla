package kz.oyla.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.R
import kz.oyla.app.ui.components.OylaBackground
import kz.oyla.app.ui.components.OylaLogo
import kz.oyla.app.ui.components.OylaPrimaryButton
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaTextMuted

@Composable
fun CreateSessionScreen(
    viewModel: SpecialistSessionViewModel,
    onBack: () -> Unit,
    onCreated: () -> Unit
) {
    BackHandler(onBack = onBack)
    val state by viewModel.uiState.collectAsState()
    var childName by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.creationSuccessId) {
        if (state.creationSuccessId != null) {
            viewModel.consumeCreationSuccess()
            onCreated()
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        OylaBackground(R.drawable.bg_specialist_home)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = maxWidth * 0.04f, vertical = maxHeight * 0.045f)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.content_description_back),
                    tint = OylaNavy
                )
            }
            OylaLogo(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.17f)
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 620.dp)
                    .fillMaxWidth(0.56f)
            ) {
                Text(
                    text = stringResource(R.string.create_session_title),
                    color = OylaNavy,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = childName,
                    onValueChange = { childName = it.take(80) },
                    enabled = !state.isLoading,
                    label = { Text(stringResource(R.string.child_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 24.sp)
                )
                state.errorMessage?.let {
                    Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                OylaPrimaryButton(
                    text = stringResource(if (state.isLoading) R.string.creating_session else R.string.create_session_action),
                    icon = Icons.Outlined.Add,
                    iconDescription = stringResource(R.string.content_description_add),
                    enabled = childName.trim().isNotEmpty() && !state.isLoading,
                    onClick = { viewModel.createSession(childName) },
                    textSize = 25.sp,
                    minHeight = 82.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.isLoading) CircularProgressIndicator(color = OylaNavy)
                Text(
                    text = stringResource(R.string.create_session_hint),
                    color = OylaTextMuted,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
