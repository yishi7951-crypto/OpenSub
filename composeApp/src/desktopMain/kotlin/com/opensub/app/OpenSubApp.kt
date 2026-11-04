package com.opensub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.opensub.app.ui.*

@Composable
fun OpenSubApp(state: DesktopState, settings: DesktopSettings, controller: DesktopController) {
    ClaudeTheme {
        Box(Modifier.fillMaxSize().background(ClaudeColors.Background)) {
            HomeScreen(HomeState(
                action = if (state.stopping) "正在停止…" else if (state.running) "停止" else if (state.busy) "加载中…" else "开始字幕",
                running = state.running, enabled = !state.busy && !state.stopping,
                note = when {
                    state.running && state.systemMuted -> "系统扬声器已静音"
                    state.running && state.level < 0.002f -> "等待视频声音"
                    else -> ""
                },
                noteIsWarning = state.running && state.systemMuted,
                preview = state.previewText, previewChinese = state.previewChinese, cues = state.cues, error = state.error,
            ), settings.style, controller::updateStyle, onAction = { if (state.running) controller.stop() else controller.start() }, onExport = controller::exportCues)
        }
    }
}
