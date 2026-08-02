package kz.oyla.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val OylaColorScheme = lightColorScheme(
    primary = OylaBlue,
    onPrimary = OylaSurface,
    secondary = OylaBlueDark,
    onSecondary = OylaSurface,
    background = OylaSurface,
    onBackground = OylaNavy,
    surface = OylaSurface,
    onSurface = OylaNavy,
    outline = OylaOutline
)

@Composable
fun OylaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OylaColorScheme,
        typography = Typography,
        content = content
    )
}
