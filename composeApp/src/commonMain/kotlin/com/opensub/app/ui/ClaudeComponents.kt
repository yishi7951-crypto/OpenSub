package com.opensub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opensub.app.core.SubtitleFont
import com.opensub.app.core.SubtitleMode
import com.opensub.app.core.SubtitleStyle
import kotlin.math.roundToInt

private const val PREVIEW_SCALE = 0.55f

expect fun subtitleFontFamily(font: SubtitleFont): FontFamily

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LabeledSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = ClaudeColors.Secondary, fontSize = 13.sp)
            Text(value, color = ClaudeColors.Muted, fontSize = 13.sp)
        }
        val fraction = ((current - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        Slider(current, onChange, valueRange = range,
            thumb = { Box(Modifier.size(18.dp).background(ClaudeColors.Text, CircleShape)) },
            track = {
                Box(Modifier.fillMaxWidth().height(4.dp).background(ClaudeColors.Raised, CircleShape)) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(ClaudeColors.Accent, CircleShape))
                }
            })
    }
}

/** Segmented control: one soft raised block marks the current choice. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onChange: (T) -> Unit) {
    Row(modifier.fillMaxWidth().background(ClaudeColors.Raised, RoundedCornerShape(10.dp)).padding(3.dp)) {
        for ((option, label) in options) {
            val active = option == selected
            Box(Modifier.weight(1f).background(if (active) ClaudeColors.Border else ClaudeColors.Raised, RoundedCornerShape(8.dp))
                .clickable { onChange(option) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (active) ClaudeColors.Text else ClaudeColors.Muted, fontSize = 14.sp)
            }
        }
    }
}

/** Always on screen: real captions once they exist, otherwise a sample, drawn with every current display choice. */
@Composable
fun SubtitleStylePreview(style: SubtitleStyle, english: String, chinese: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
        val size = style.fontSize * PREVIEW_SCALE
        val bilingual = style.mode == SubtitleMode.Bilingual
        val family = subtitleFontFamily(style.font)
        val englishLine = @Composable {
            val scaled = style.englishFontSize * PREVIEW_SCALE
            if (style.mode != SubtitleMode.Chinese && english.isNotEmpty()) Text(english, color = if (bilingual) ClaudeColors.Secondary else ClaudeColors.Text,
                fontFamily = family, fontSize = scaled.roundToInt().sp, lineHeight = (scaled * 1.3f).sp, textAlign = TextAlign.Center)
        }
        val chineseLine = @Composable {
            if (style.mode != SubtitleMode.English && chinese.isNotEmpty()) Text(chinese, color = ClaudeColors.Text, fontFamily = family, fontSize = size.roundToInt().sp, lineHeight = (size * 1.3f).sp, textAlign = TextAlign.Center)
        }
        Column(Modifier.background(ClaudeColors.Caption.copy(alpha = style.backgroundOpacity), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (style.chineseFirst) chineseLine()
            englishLine()
            if (!style.chineseFirst) chineseLine()
        }
    }
}
