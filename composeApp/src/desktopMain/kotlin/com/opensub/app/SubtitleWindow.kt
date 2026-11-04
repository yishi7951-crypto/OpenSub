package com.opensub.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.opensub.app.core.SubtitleCue
import com.opensub.app.core.SubtitleMode
import kotlinx.coroutines.flow.collect
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.math.roundToInt

@Composable
fun SubtitleWindow(settings: DesktopSettings, cue: SubtitleCue?, chinese: String, controller: DesktopController) {
    val reveal = rememberWordReveal(cue)
    val bounds = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds }
    val width = subtitleWindowWidth()
    val initial = remember {
        val saved = settings.overlayX?.let { x -> settings.overlayY?.let { y -> Rectangle(x, y, width, 120) } }
        val visible = saved != null && GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.intersects(saved) }
        if (visible) WindowPosition.Absolute(minOf(saved!!.x, bounds.x + bounds.width - width).dp, saved.y.dp)
        else WindowPosition.Absolute((bounds.x + (bounds.width - width) / 2).dp, (bounds.y + bounds.height - 300).coerceAtLeast(bounds.y).dp)
    }
    val windowState = rememberWindowState(position = initial, width = width.dp, height = 240.dp)
    LaunchedEffect(settings.style.fontSize, settings.style.englishFontSize) { windowState.size = DpSize(width.dp, ((settings.style.fontSize + settings.style.englishFontSize) * 3 + 60).dp) }
    LaunchedEffect(windowState) {
        snapshotFlow { windowState.position }.collect { position ->
            (position as? WindowPosition.Absolute)?.let { controller.updatePosition(it.x.value.roundToInt(), it.y.value.roundToInt()) }
        }
    }
    Window(onCloseRequest = {}, title = "OpenSub 字幕", visible = cue != null && (settings.style.mode != SubtitleMode.Chinese || chinese.isNotEmpty()),
        state = windowState, undecorated = true, transparent = true, alwaysOnTop = true,
        resizable = false, focusable = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            if (cue != null) WindowDraggableArea {
                SubtitleCard(reveal, settings.style, chinese, cue.id)
            }
        }
    }
}
