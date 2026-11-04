package com.opensub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.opensub.app.core.SentenceCaption
import com.opensub.app.core.SubtitleCue
import com.opensub.app.core.SubtitleFont
import com.opensub.app.core.SubtitleStyle
import com.opensub.app.core.WordReveal
import com.opensub.app.core.WordRevealFrame
import com.opensub.app.core.subtitlePunctuation
import com.opensub.app.platform.AppPaths
import com.opensub.app.ui.ClaudeColors
import com.opensub.app.ui.ClaudeTheme
import com.opensub.app.ui.HomeScreen
import com.opensub.app.ui.HomeState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** Renders the real main screen offscreen, so layout can be reviewed without opening a window. */
@OptIn(ExperimentalComposeUiApi::class)
fun renderUiPreview() {
    val output = File(AppPaths.home, "out/previews").apply { mkdirs() }
    val controller = DesktopController()
    val cues = listOf(
        SubtitleCue(1, 18_000, 21_000, "The greater our ignorance unfolds.", 80, chinese = "我们的无知越大。"),
        SubtitleCue(2, 21_000, 24_000, "Despite the striking fact.", 75, chinese = "尽管事实令人吃惊"),
    )
    val states = mapOf("idle" to DesktopState(), "running" to DesktopState(running = true, elapsedMs = 83_000, level = 0.06f, cues = cues, previewText = "We choose to go to the moon"), "song" to DesktopState(cues = cues))
    for ((name, state) in states) {
        val scene = ImageComposeScene(width = 720, height = 620, density = Density(1f)) { OpenSubApp(state, DesktopSettings(SubtitleStyle(font = if (name == "song") SubtitleFont.Serif else SubtitleFont.Sans)), controller) }
        try {
            scene.render(0)
            scene.render(1_000_000_000).use { bitmap -> bitmap.encodeToData(EncodedImageFormat.PNG)!!.use { File(output, "desktop-$name.png").writeBytes(it.bytes) } }
        } finally { scene.close() }
    }
    // The shared home screen at the tablet's portrait and landscape sizes (736 x 1120 dp) and a phone's, as Android shows it.
    val many = (1L..9L).map { SubtitleCue(it, it * 3_000, it * 3_000 + 2_000, "Sentence number $it, as it was heard.", 80, chinese = "这是听到的第 $it 句。") }
    val home = HomeState("停止", running = true, enabled = true, preview = "We choose to go to the moon in this decade", previewChinese = "我们选择在这十年内登月", cues = many)
    for ((name, size) in mapOf("tablet-portrait" to (736 to 1120), "tablet-landscape" to (1120 to 736), "phone-portrait" to (392 to 860), "phone-landscape" to (860 to 392))) {
        val scene = ImageComposeScene(width = size.first, height = size.second, density = Density(1f)) {
            ClaudeTheme { Box(Modifier.fillMaxSize().background(ClaudeColors.Background)) { HomeScreen(home, SubtitleStyle(), {}, {}, {}) } }
        }
        try {
            scene.render(0)
            scene.render(1_000_000_000).use { bitmap -> bitmap.encodeToData(EncodedImageFormat.PNG)!!.use { File(output, "android-$name.png").writeBytes(it.bytes) } }
        } finally { scene.close() }
    }
    println("UI PREVIEW: ${output.absolutePath}")
}

/** Renders the production subtitle composable offscreen, one sentence after another; no window, audio, or input injection. */
@OptIn(ExperimentalComposeUiApi::class)
fun renderAnimationPreview() {
    val output = File(AppPaths.home, "out/previews/word-reveal-frames").apply { mkdirs() }
    val style = SubtitleStyle(fontSize = 40f, englishFontSize = 40f, backgroundOpacity = 0.54f, font = SubtitleFont.Serif)
    val caption = SentenceCaption({ englishRowChars(style) }, { chineseRowChars(style) })
    val reveal = WordReveal()
    var frame by mutableStateOf(WordRevealFrame())
    var chinese by mutableStateOf("")
    var sentence by mutableStateOf(0L)
    val scene = ImageComposeScene(width = 1800, height = 320, density = Density(1f)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF26323F)), contentAlignment = Alignment.BottomCenter) {
            SubtitleCard(frame, style, chinese, sentence)
        }
    }
    class Piece(val atMs: Long, val sentence: Long, val english: String, val unit: Long = 0, val chinese: String = "")
    val updates = listOf(
        Piece(0, 1, "The first thing I want to talk about is pretty serious, okay."),
        Piece(500, 1, "The first thing I want to talk about is pretty serious, okay.", 1, "我想首先谈论的是一件相当严肃的事情"),
        Piece(1000, 2, "Need all of you to take this seriously,"),
        Piece(1100, 1, "The first thing I want to talk about is pretty serious, okay.", 2, "好的"),
        Piece(2000, 2, "Need all of you to take this seriously, put your phones down,", 3, "我希望你们所有人都认真对待这件事"),
        Piece(3000, 2, "Need all of you to take this seriously, put your phones down, put your laptop down", 4, "请放下手机"),
        Piece(4000, 2, "Need all of you to take this seriously, put your phones down, put your laptop down, put your water bottles down.", 5, "放下你的笔记本电脑"),
    )
    var next = 0
    try {
        repeat(75) { index ->
            val ms = index * 1000L / 15
            while (next < updates.size && updates[next].atMs <= ms) {
                val update = updates[next++]
                caption.english(update.sentence, update.english, update.atMs)
                if (update.unit > 0) caption.chinese(update.sentence, update.unit, subtitlePunctuation(update.chinese), update.atMs)
            }
            val shown = caption.advance(ms)
            sentence = shown.sentence
            chinese = shown.chinese
            reveal.target(shown.sentence, shown.english, ms)
            frame = reveal.frame(ms)
            scene.render(ms * 1_000_000).use { bitmap ->
                bitmap.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    File(output, "frame-%03d.png".format(index)).writeBytes(data.bytes)
                }
            }
        }
    } finally { scene.close() }
    println("ANIMATION PREVIEW: ${output.absolutePath}")
}
