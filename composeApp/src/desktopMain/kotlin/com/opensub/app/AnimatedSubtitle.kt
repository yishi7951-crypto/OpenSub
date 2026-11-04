package com.opensub.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opensub.app.core.SubtitleCue
import com.opensub.app.core.SubtitleMode
import com.opensub.app.core.SubtitleStyle
import com.opensub.app.core.WordReveal
import com.opensub.app.core.WordRevealFrame
import com.opensub.app.core.balancedRows
import com.opensub.app.core.filledRows
import com.opensub.app.core.subtitlePunctuation
import com.opensub.app.ui.ClaudeColors
import com.opensub.app.ui.subtitleFontFamily
import java.awt.GraphicsEnvironment
import kotlin.time.TimeSource

@Composable
fun rememberWordReveal(cue: SubtitleCue?): WordRevealFrame {
    val reveal = remember { WordReveal() }
    val clock = remember { TimeSource.Monotonic.markNow() }
    var frame by remember { mutableStateOf(WordRevealFrame()) }
    LaunchedEffect(cue?.id, cue?.english) {
        reveal.target(cue?.id, cue?.english ?: "", clock.elapsedNow().inWholeMilliseconds)
        do {
            frame = reveal.frame(clock.elapsedNow().inWholeMilliseconds)
            if (frame.animating) withFrameNanos { }
        } while (frame.animating)
    }
    return frame
}

private const val CARD_MARGINS = 72
/** Wide enough for a whole clause on one line. */
private const val WIDEST_WINDOW = 1700
private const val SCREEN_SHARE = 0.9f
private const val LATIN_WIDTH = 0.55f
/**
 * Rows as long as film subtitles allow (Netflix keeps English to 42 characters): two rows roll up, and longer rows made a
 * wall of text in real use. Chinese rows are about as wide on screen; at 18 a long subtitle took three rows and its start
 * rolled away at once.
 */
private const val LONGEST_LINE_CHARS = 42
private const val LONGEST_CHINESE_CHARS = 22
private const val GROW_MS = 160
/** Two rows of full-size text would take too much of the picture. */
private const val TWO_ROW_SIZE = 0.85f

fun subtitleWindowWidth(): Int {
    if (GraphicsEnvironment.isHeadless()) return 1060
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds.width
    return (screen * SCREEN_SHARE).toInt().coerceIn(480, WIDEST_WINDOW)
}

/** English characters that fit one row of the card. */
fun englishRowChars(style: SubtitleStyle) = ((subtitleWindowWidth() - CARD_MARGINS) / (style.englishFontSize * LATIN_WIDTH)).toInt().coerceAtMost(LONGEST_LINE_CHARS)

/** English characters the card shows at once: a sentence too long for one row takes two, smaller. */
fun englishLineChars(style: SubtitleStyle) = englishRowChars(style) * 2

/** Chinese characters that fit one row of the card. */
fun chineseRowChars(style: SubtitleStyle) = ((subtitleWindowWidth() - CARD_MARGINS) / style.fontSize).toInt().coerceAtMost(LONGEST_CHINESE_CHARS)

/** The size at which [characters] of this width fit one line: text gets smaller rather than wrap or lose its end. */
private fun fittedSize(size: Float, characters: Int, characterWidth: Float): Float {
    val needed = characters * size * characterWidth
    val available = (subtitleWindowWidth() - CARD_MARGINS).toFloat()
    return if (needed <= available) size else size * available / needed
}

/** The size for [rows], one or two of them, smaller when there are two. */
private fun rowSize(size: Float, rows: String, characterWidth: Float) =
    fittedSize(if ('\n' in rows) size * TWO_ROW_SIZE else size, rows.lines().maxOf { it.length }, characterWidth)

/** Grows out from the centre when a caption gets longer, rather than jumping. A new caption just starts at its own size. */
@Composable
private fun GrowingLine(caption: Long?, content: @Composable () -> Unit) = key(caption) {
    Box(Modifier.animateContentSize(tween(GROW_MS))) { content() }
}

/** One caption: English and Chinese each on one centred line, or two smaller ones when a long sentence needs them. */
@Composable
fun SubtitleCard(frame: WordRevealFrame, style: SubtitleStyle, chinese: String = "", caption: Long? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(12.dp).shadow(10.dp, RoundedCornerShape(14.dp))
        .background(ClaudeColors.Caption.copy(alpha = style.backgroundOpacity), RoundedCornerShape(14.dp))
        .padding(horizontal = 24.dp, vertical = 16.dp)) {
        val bilingual = style.mode == SubtitleMode.Bilingual
        val english = if (bilingual) ClaudeColors.Secondary else ClaudeColors.Text
        val englishRows = filledRows(frame.text, englishRowChars(style))
        val text = buildAnnotatedString {
            append(englishRows)
            addStyle(SpanStyle(color = Color.Transparent), 0, englishRows.length)
            for (word in frame.words) addStyle(SpanStyle(color = english.copy(alpha = word.opacity)), word.start, word.end)
        }
        val englishSize = rowSize(style.englishFontSize, englishRows, LATIN_WIDTH)
        val rows = subtitlePunctuation(balancedRows(chinese, chineseRowChars(style)))
        val chineseSize = rowSize(style.fontSize, rows, 1f)
        val family = subtitleFontFamily(style.font)
        val chineseLine = @Composable {
            // Empty lines stay reserved, so the card keeps one size whatever is on it.
            if (style.mode != SubtitleMode.English) GrowingLine(caption) {
                Text(rows, color = ClaudeColors.Text, fontFamily = family, fontSize = chineseSize.sp, lineHeight = (chineseSize * 1.35f).sp,
                    textAlign = TextAlign.Center, minLines = 1, maxLines = 2, softWrap = false)
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (style.chineseFirst) chineseLine()
            if (style.mode != SubtitleMode.Chinese) GrowingLine(caption) {
                Text(text, fontFamily = family, fontSize = englishSize.sp, lineHeight = (englishSize * 1.35f).sp,
                    textAlign = TextAlign.Center, minLines = 1, maxLines = 2, softWrap = false)
            }
            if (!style.chineseFirst) chineseLine()
        }
    }
}
