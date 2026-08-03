package kz.oyla.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import kz.oyla.app.R
import kz.oyla.app.ui.theme.OylaBlue
import kz.oyla.app.ui.theme.OylaBlueDark
import kz.oyla.app.ui.theme.OylaDisabled
import kz.oyla.app.ui.theme.OylaNavy

@Composable
fun OylaBackground(@DrawableRes backgroundRes: Int) {
    Image(
        painter = painterResource(backgroundRes),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun OylaLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.oyla_logo),
        contentDescription = androidx.compose.ui.res.stringResource(R.string.content_description_logo),
        contentScale = ContentScale.Fit,
        modifier = modifier.widthIn(max = 230.dp)
    )
}

@Composable
fun OylaPrimaryButton(
    text: String,
    icon: ImageVector,
    iconDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textSize: TextUnit = 22.sp,
    minHeight: Dp = 64.dp
) {
    val shape = RoundedCornerShape(28.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.White
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier
            .heightIn(min = minHeight)
            .semantics { contentDescription = text }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                // A button child receives the Column's available height. Filling that height
                // makes every OylaPrimaryButton expand to the entire section.
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        listOf(if (enabled) OylaBlue else OylaDisabled, if (enabled) OylaBlueDark else OylaDisabled)
                    ),
                    shape = shape
                )
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconDescription,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = textSize,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
