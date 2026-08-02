package kz.oyla.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Person
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
import kz.oyla.app.ui.theme.OylaBlueLight
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaSurface

@Composable
fun RoleSelectionScreen(onRoleSelected: (DeviceRole) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = maxWidth * 0.06f, vertical = maxHeight * 0.05f)
        ) {
            OylaLogo(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.19f)
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(0.58f)
                    .fillMaxHeight(0.82f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.role_selection_title),
                    color = OylaNavy,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 34.sp
                )
                OylaPrimaryButton(
                    text = stringResource(R.string.role_specialist),
                    icon = Icons.Outlined.Person,
                    iconDescription = stringResource(R.string.role_specialist),
                    onClick = { onRoleSelected(DeviceRole.SPECIALIST) },
                    modifier = Modifier.fillMaxWidth(),
                    textSize = 24.sp
                )
                OylaPrimaryButton(
                    text = stringResource(R.string.role_child),
                    icon = Icons.Outlined.ChildCare,
                    iconDescription = stringResource(R.string.role_child),
                    onClick = { onRoleSelected(DeviceRole.CHILD) },
                    modifier = Modifier.fillMaxWidth(),
                    textSize = 24.sp
                )
            }
        }
    }
}
