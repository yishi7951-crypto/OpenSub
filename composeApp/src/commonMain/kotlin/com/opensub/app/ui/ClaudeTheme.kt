package com.opensub.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Warm neutrals and terracotta, matching Claude's dark mode. Shared by both platforms. */
object ClaudeColors {
    val Background = Color(0xFF262624)
    val Surface = Color(0xFF30302E)
    val Raised = Color(0xFF3A3A37)
    val Border = Color(0xFF474743)
    val Text = Color(0xFFFAF9F5)
    val Secondary = Color(0xFFC2C0B6)
    val Muted = Color(0xFF9C9A92)
    val Accent = Color(0xFFD97757)
    val AccentStrong = Color(0xFFC6613F)
    val Warning = Color(0xFFE5B567)
    val Error = Color(0xFFF2A79B)
    val ErrorSurface = Color(0xFF4A2E29)
    /** Overlay card base. Darker than Background so subtitles hold up over bright video. */
    val Caption = Color(0xFF1F1E1D)
}

/** Claude buttons are soft rectangles, not Material pills. */
val ClaudeButton = RoundedCornerShape(10.dp)

/** Headings use a serif face, as Claude does; body text stays on the system sans. */
val ClaudeHeading = FontFamily.Serif

@Composable
fun ClaudeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ClaudeColors.AccentStrong, onPrimary = ClaudeColors.Text,
            secondary = ClaudeColors.Accent, onSecondary = ClaudeColors.Caption,
            background = ClaudeColors.Background, onBackground = ClaudeColors.Text,
            surface = ClaudeColors.Surface, onSurface = ClaudeColors.Text,
            surfaceVariant = ClaudeColors.Raised, onSurfaceVariant = ClaudeColors.Secondary,
            secondaryContainer = ClaudeColors.Raised, onSecondaryContainer = ClaudeColors.Text,
            surfaceContainerHighest = ClaudeColors.Border,
            outline = ClaudeColors.Border, outlineVariant = ClaudeColors.Border,
            error = ClaudeColors.Error, errorContainer = ClaudeColors.ErrorSurface,
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}
