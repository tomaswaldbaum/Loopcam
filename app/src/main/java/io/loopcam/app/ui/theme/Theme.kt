package io.loopcam.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LoopCamColors = darkColorScheme(
    primary = Color(0xFFFF4F5E),
    onPrimary = Color.White,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
)

@Composable
fun LoopCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LoopCamColors, content = content)
}
