package com.opensub.app.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import com.opensub.app.core.SubtitleCue
import java.io.File

data class CaptureUiState(
    val recording: Boolean = false, val status: String = "",
    val recordedMs: Long = 0, val level: Float = 0f, val error: String? = null,
    val cues: List<SubtitleCue> = emptyList(), val previewText: String = "", val previewChinese: String = "",
    val previewCount: Int = 0, val queued: Int = 0, val lastDecodeMs: Long = 0, val lastTranslateMs: Long = 0,
)

object CaptureState {
    private val mutable = MutableStateFlow(CaptureUiState())
    val state = mutable.asStateFlow()
    fun update(transform: (CaptureUiState) -> CaptureUiState) = mutable.update(transform)
    fun persist(context: Context) {
        val current = mutable.value
        val report = JSONObject().put("recording", current.recording).put("status", current.status)
            .put("recordedMs", current.recordedMs).put("level", current.level).put("error", current.error ?: "")
            .put("cueCount", current.cues.size).put("previewCount", current.previewCount).put("queued", current.queued)
            .put("lastDecodeMs", current.lastDecodeMs).put("lastTranslateMs", current.lastTranslateMs)
            .put("translatedCount", current.cues.count { it.chinese.isNotEmpty() })
        File(context.filesDir, "capture-status.json").writeText(report.toString(2))
    }
}
