package kz.oyla.app.ui.specialist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
fun SpecialistHomeScreen(
    onNewLesson: () -> Unit,
    onOpenSettings: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val contentTopPadding = maxHeight * 0.07f
        OylaBackground(R.drawable.bg_specialist_home)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = maxWidth * 0.04f, vertical = maxHeight * 0.045f)
        ) {
            OylaLogo(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.17f)
            )
            SpecialistSettingsButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .widthIn(min = 190.dp, max = 250.dp)
                    .fillMaxWidth(0.18f)
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 680.dp)
                    .fillMaxWidth(0.52f)
                    .padding(top = contentTopPadding)
            ) {
                Text(
                    text = stringResource(R.string.specialist_welcome),
                    color = OylaNavy,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 50.sp,
                        lineHeight = 58.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.specialist_subtitle),
                    color = OylaTextMuted,
                    fontSize = 29.sp,
                    lineHeight = 36.sp,
                    textAlign = TextAlign.Center
                )
                OylaPrimaryButton(
                    text = stringResource(R.string.new_lesson),
                    icon = Icons.Outlined.Add,
                    iconDescription = stringResource(R.string.content_description_add),
                    onClick = onNewLesson,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 126.dp)
                        .padding(top = 26.dp)
                )
            }
        }
    }
}

@Composable
private fun SpecialistSettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Button(
        onClick = onClick,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = OylaNavy
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 7.dp),
        modifier = modifier
            .heightIn(min = 72.dp)
            .shadow(4.dp, shape)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.content_description_settings),
                modifier = Modifier
                    .size(32.dp)
                    .padding(end = 8.dp)
            )
            Text(
                text = stringResource(R.string.settings),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
