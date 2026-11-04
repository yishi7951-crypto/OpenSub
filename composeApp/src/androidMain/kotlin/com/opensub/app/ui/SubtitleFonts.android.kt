package com.opensub.app.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.opensub.app.R
import com.opensub.app.core.SubtitleFont

// Many Android tablets have no Chinese serif face, so FontFamily.Serif would quietly fall back to sans.
private val Song = FontFamily(Font(R.font.noto_serif_sc))

actual fun subtitleFontFamily(font: SubtitleFont): FontFamily = when (font) {
    SubtitleFont.Sans -> FontFamily.SansSerif
    SubtitleFont.Serif -> Song
}
