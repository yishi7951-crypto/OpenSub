package com.opensub.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opensub.app.core.ExportFormat
import com.opensub.app.core.SubtitleCue
import com.opensub.app.core.SubtitleFont
import com.opensub.app.core.SubtitleMode
import com.opensub.app.core.SubtitleStyle
import com.opensub.app.core.subtitleLines
import kotlin.math.roundToInt

private const val SAMPLE_ENGLISH = "We choose to go to the moon."
private const val SAMPLE_CHINESE = "我们选择登月。"
/** Narrower than this, a phone upright, the sliders take a row each. */
private val NARROW = 520.dp

data class HomeState(
    val action: String, val running: Boolean, val enabled: Boolean,
    val note: String = "", val noteIsWarning: Boolean = false,
    val preview: String = "", val previewChinese: String = "", val cues: List<SubtitleCue> = emptyList(), val error: String? = null,
)

/** The only screen. The preview stays visible throughout, so style changes can always be judged. */
@Composable
fun HomeScreen(state: HomeState, style: SubtitleStyle, onStyle: (SubtitleStyle) -> Unit, onAction: () -> Unit, onExport: (ExportFormat) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 620.dp).fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("OpenSub", color = ClaudeColors.Text, fontFamily = ClaudeHeading, fontSize = 36.sp, lineHeight = 46.sp)
            Spacer(Modifier.height(24.dp))
            val size = Modifier.widthIn(min = 180.dp).height(52.dp)
            if (state.running) OutlinedButton(onAction, size, state.enabled, ClaudeButton) { Text(state.action, color = ClaudeColors.Text, fontSize = 16.sp) }
            else Button(onAction, size, state.enabled, ClaudeButton) { Text(state.action, fontSize = 16.sp) }
            Column(Modifier.weight(1f).fillMaxWidth().clipToBounds().padding(vertical = 20.dp), Arrangement.spacedBy(14.dp), Alignment.CenterHorizontally) {
                val line = Modifier.fillMaxWidth()
                if (state.error != null) Text(state.error, line, color = ClaudeColors.Error, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                if (state.note.isNotEmpty()) Text(state.note, line, color = if (state.noteIsWarning) ClaudeColors.Warning else ClaudeColors.Muted, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
                val latest = state.cues.lastOrNull()
                val waiting = latest == null && state.preview.isEmpty()
                SubtitleStylePreview(style, if (waiting) SAMPLE_ENGLISH else state.preview.ifEmpty { latest?.english.orEmpty() }, if (waiting) SAMPLE_CHINESE else state.previewChinese.ifEmpty { latest?.chinese.orEmpty() })
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.cues.dropLast(1).asReversed(), key = { it.id }) { cue ->
                        Column(line) {
                            for (text in subtitleLines(cue, style)) Text(text, line, color = ClaudeColors.Muted, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            if (state.cues.isNotEmpty()) {
                Box {
                    var choosing by remember { mutableStateOf(false) }
                    TextButton({ choosing = true }) { Text("导出本次字幕", color = ClaudeColors.Accent, fontSize = 15.sp) }
                    DropdownMenu(choosing, { choosing = false }, containerColor = ClaudeColors.Raised) {
                        for (format in ExportFormat.entries) DropdownMenuItem({ Text(format.label, color = ClaudeColors.Text, fontSize = 15.sp) }, { choosing = false; onExport(format) })
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Segmented(SubtitleMode.entries.map { it to it.label }, style.mode) { onStyle(style.copy(mode = it)) }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(SubtitleFont.entries.map { it to it.label }, style.font, Modifier.weight(1f)) { onStyle(style.copy(font = it)) }
                // Line order only means something when both languages are on screen.
                if (style.mode == SubtitleMode.Bilingual) Segmented(listOf(true to "中文在上", false to "英文在上"), style.chineseFirst, Modifier.weight(1f)) { onStyle(style.copy(chineseFirst = it)) }
            }
            Spacer(Modifier.height(20.dp))
            // A phone held upright has no room for three sliders side by side ("75%" fell onto a second line), so they stack.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val both = style.mode == SubtitleMode.Bilingual
                val sliders = @Composable { each: Modifier ->
                    if (style.mode != SubtitleMode.English) LabeledSlider(if (both) "中文字号" else "字号", "${style.fontSize.roundToInt()}", style.fontSize, 20f..64f, each) { onStyle(style.copy(fontSize = it)) }
                    if (style.mode != SubtitleMode.Chinese) LabeledSlider(if (both) "英文字号" else "字号", "${style.englishFontSize.roundToInt()}", style.englishFontSize, 20f..64f, each) { onStyle(style.copy(englishFontSize = it)) }
                    LabeledSlider("背景不透明度", "${(style.backgroundOpacity * 100).roundToInt()}%", style.backgroundOpacity, 0.15f..1f, each) { onStyle(style.copy(backgroundOpacity = it)) }
                }
                if (maxWidth < NARROW) Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) { sliders(Modifier.fillMaxWidth()) }
                else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) { sliders(Modifier.weight(1f)) }
            }
        }
    }
}
