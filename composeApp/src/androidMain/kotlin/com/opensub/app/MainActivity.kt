package com.opensub.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.opensub.app.capture.CaptureState
import com.opensub.app.capture.CaptureUiState
import com.opensub.app.capture.PlaybackCaptureService
import com.opensub.app.speech.SpeechProbe
import com.opensub.app.core.ExportFormat
import com.opensub.app.core.SubtitleCue
import com.opensub.app.core.SubtitleStyle
import com.opensub.app.export.exportSubtitles
import com.opensub.app.display.SubtitlePreferences
import com.opensub.app.ui.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val EXPORT_FORMAT = "exportFormat"

class MainActivity : ComponentActivity() {
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) requestRecordingPermissions()
        else CaptureState.update { it.copy(error = "需要允许 OpenSub 显示在其他应用上层，才能显示视频字幕。") }
    }
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startForegroundService(Intent(this, PlaybackCaptureService::class.java).putExtra(PlaybackCaptureService.EXTRA_CONSENT, data))
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestCaptureConsent()
        else CaptureState.update { it.copy(error = "需要录音权限才能获取系统播放声音；本应用不使用麦克风。") }
    }
    private var exportFormat = ExportFormat.Word
    private val exporter = registerForActivityResult(object : ActivityResultContracts.CreateDocument("*/*") {
        override fun createIntent(context: Context, input: String) = super.createIntent(context, input).setType(exportFormat.mime)
    }) { uri ->
        if (uri != null) try {
            val bytes = exportSubtitles(CaptureState.state.value.cues, SubtitlePreferences.read(this), exportFormat)
            checkNotNull(contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        } catch (e: Exception) { CaptureState.update { it.copy(error = "导出失败：${e.message}") } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(EXPORT_FORMAT)?.let { name -> exportFormat = ExportFormat.valueOf(name) }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            val state by CaptureState.state.collectAsState()
            var style by remember { mutableStateOf(SubtitlePreferences.read(this)) }
            CaptureScreen(state, style, ::beginCaptions, ::stopCapture, ::exportCues) { value ->
                style = value; SubtitlePreferences.write(this, value)
                if (state.recording) startService(Intent(this, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.ACTION_STYLE))
            }
        }
        handleDiagnostic(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleDiagnostic(intent) }
    private fun handleDiagnostic(request: Intent) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        when (request.getStringExtra("diagnostic")) {
            "speech" -> SpeechProbe.run(applicationContext)
            "rolling" -> SpeechProbe.rolling(applicationContext, request.getIntExtra("threads", 2), request.getBooleanExtra("overlay", false), request.getIntExtra("hyThreads", 4), request.getBooleanExtra("guess", false))
            "soak" -> SpeechProbe.soak(applicationContext, request.getIntExtra("minutes", 3), request.getBooleanExtra("overlay", false))
            "video" -> startActivity(Intent().setClassName(this, "com.opensub.app.testing.TestVideoActivity"))
            "cues" -> CaptureState.update { state -> state.copy(cues = (1L..16L).map { SubtitleCue(it, it * 3_000, it * 3_000 + 2_000, "Sentence number $it of the test.", 0, chinese = "这是测试的第 $it 句。") }) }
        }
    }
    private fun beginCaptions() {
        CaptureState.update { it.copy(error = null) }
        if (!Settings.canDrawOverlays(this)) {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else requestRecordingPermissions()
    }
    private fun requestRecordingPermissions() {
        val required = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (required.isEmpty()) requestCaptureConsent() else permissions.launch(required.toTypedArray())
    }
    private fun requestCaptureConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        captureConsent.launch(request)
    }
    private fun exportCues(format: ExportFormat) {
        exportFormat = format
        exporter.launch("OpenSub-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "." + format.extension)
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString(EXPORT_FORMAT, exportFormat.name); super.onSaveInstanceState(outState) }
    private fun stopCapture() {
        startService(Intent(this, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.ACTION_STOP))
    }
}

@Composable
private fun CaptureScreen(state: CaptureUiState, style: SubtitleStyle, start: () -> Unit, stop: () -> Unit, export: (ExportFormat) -> Unit, changeStyle: (SubtitleStyle) -> Unit) {
    val silent = state.recording && state.recordedMs > 3000 && state.level < 0.001f
    ClaudeTheme {
        Box(Modifier.fillMaxSize().background(ClaudeColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            HomeScreen(HomeState(
                action = if (state.recording) "停止" else "开始字幕", running = state.recording, enabled = true,
                note = when {
                    silent -> "暂时没有抓到声音"
                    state.recording && state.recordedMs == 0L -> state.status
                    else -> ""
                },
                noteIsWarning = silent, preview = state.previewText, previewChinese = state.previewChinese, cues = state.cues, error = state.error,
            ), style, changeStyle, onAction = if (state.recording) stop else start, onExport = export)
        }
    }
}
