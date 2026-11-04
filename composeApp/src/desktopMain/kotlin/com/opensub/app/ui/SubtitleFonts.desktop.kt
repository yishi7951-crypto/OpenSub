package com.opensub.app.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.opensub.app.core.SubtitleFont

// System SimSun draws Latin text in a narrow monospace, so both platforms share one bundled face.
private val Song = FontFamily(Font("font/noto_serif_sc.ttf"))

actual fun subtitleFontFamily(font: SubtitleFont): FontFamily = when (font) {
    SubtitleFont.Sans -> FontFamily.SansSerif
    SubtitleFont.Serif -> Song
}
