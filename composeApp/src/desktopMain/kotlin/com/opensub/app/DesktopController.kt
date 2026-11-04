package com.opensub.app

import com.opensub.app.core.*
import com.opensub.app.export.exportSubtitles
import com.opensub.app.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlin.math.sqrt

data class DesktopSettings(
    val style: SubtitleStyle = SubtitleStyle(),
    val overlayX: Int? = null, val overlayY: Int? = null,
)

data class DesktopState(
    val running: Boolean = false, val busy: Boolean = false, val stopping: Boolean = false,
    val status: String = "尚未开始，请点击「开始字幕」", val error: String? = null,
    val format: String = "系统默认播放设备", val level: Float = 0f,
    val systemMuted: Boolean = false,
    val elapsedMs: Long = 0, val queued: Int = 0, val cues: List<SubtitleCue> = emptyList(),
    val previewCount: Int = 0, val lastDecodeMs: Long = 0, val lastQueueMs: Long = 0, val lastLatencyMs: Long = 0,
    val lastTranslateMs: Long = 0,
    val previewText: String = "", val previewChinese: String = "",
)

class DesktopController : SubtitleDisplay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(DesktopState())
    val state = mutableState.asStateFlow()
    private val mutableSettings = MutableStateFlow(loadSettings())
    val settings = mutableSettings.asStateFlow()
    private val mutableCue = MutableStateFlow<SubtitleCue?>(null)
    val cue = mutableCue.asStateFlow()
    private val mutableChinese = MutableStateFlow("")
    val chinese = mutableChinese.asStateFlow()
    private var source: AudioSource? = null
    private var session: Job? = null
    private var expiry: Job? = null
    private val captionLock = Any()
    private val caption = SentenceCaption({ englishRowChars(settings.value.style) }, { chineseRowChars(settings.value.style) },
        { settings.value.style.mode != SubtitleMode.English })
    private val captionClock = kotlin.time.TimeSource.Monotonic.markNow()
    /** Lets the next sentence in when the one on screen has had its time. */
    private var nextTurn: Job? = null
    init {
        scope.launch {
            @OptIn(FlowPreview::class)
            settings.drop(1).debounce(400).collect { saveSettings(it) }
        }
        scope.launch {
            @OptIn(FlowPreview::class)
            state.sample(1000).collect { current ->
                Properties().apply {
                    setProperty("pid", ProcessHandle.current().pid().toString())
                    setProperty("updatedAt", java.time.Instant.now().toString())
                    setProperty("running", current.running.toString())
                    setProperty("busy", current.busy.toString())
                    setProperty("status", current.status)
                    setProperty("systemMuted", current.systemMuted.toString())
                    setProperty("capture", current.format)
                    setProperty("level", current.level.toString())
                    setProperty("elapsedMs", current.elapsedMs.toString())
                    setProperty("cueCount", current.cues.size.toString())
                    setProperty("queued", current.queued.toString())
                    setProperty("previewCount", current.previewCount.toString())
                    setProperty("lastDecodeMs", current.lastDecodeMs.toString())
                    setProperty("lastQueueMs", current.lastQueueMs.toString())
                    setProperty("lastLatencyMs", current.lastLatencyMs.toString())
                    setProperty("lastTranslateMs", current.lastTranslateMs.toString())
                    setProperty("error", current.error ?: "")
                    File(AppPaths.logs, "runtime-status.properties").outputStream().use { store(it, "OpenSub runtime status") }
                }
            }
        }
    }
    fun start() {
        if (state.value.busy || state.value.running) return
        clear()
        mutableState.update { it.copy(busy = true, error = null, status = "正在加载离线模型…", elapsedMs = 0, cues = emptyList(),
            previewCount = 0, previewText = "", previewChinese = "", lastDecodeMs = 0, lastQueueMs = 0, lastLatencyMs = 0) }
        session = scope.launch {
            var detector: VadEngine? = null
            var recognizer: ParakeetRecognizer? = null
            var translator: Translator? = null
            try {
                detector = rollingVad()
                recognizer = ParakeetRecognizer()
                translator = CachingTranslator(desktopTranslator())
                val deviceLoopback = WasapiAudioSource(
                    onFormat = { format -> mutableState.update { it.copy(format = format.toString(), systemMuted = format.systemMuted) } },
                    onMuteChanged = { muted -> mutableState.update { it.copy(systemMuted = muted) } })
                // Other programs' own sound, before the system volume: muted speakers still give subtitles.
                mutableState.update { it.copy(format = "各程序的声音（不受系统音量影响）") }
                val audio = ProcessLoopbackAudioSource(AppPaths.audioCapture, deviceLoopback,
                    onFallback = { mutableState.update { it.copy(format = "系统默认播放设备") } })
                source = audio
                mutableState.update { it.copy(busy = false, running = true, status = "正在聆听系统声音") }
                var lastMeterMs = 0L
                // What this session put on screen, to see real use the way a replay shows a test.
                val shownLog = File(AppPaths.logs, "live-captions.log").apply { writeText("") }
                // Whole sentences: the window shows one at a time with its own Chinese, rather than turning a page within one.
                RollingCaptionPipeline().run(audio, detector, recognizer, translator = translator,
                    onFrame = { frame ->
                        val nowMs = frame.endSample * 1000 / SAMPLE_RATE
                        if (nowMs - lastMeterMs >= 80) {
                            lastMeterMs = nowMs
                            val level = sqrt(frame.samples.sumOf { (it * it).toDouble() } / frame.samples.size).toFloat()
                            mutableState.update { it.copy(elapsedMs = nowMs, level = level) }
                        }
                    },
                    onTranslation = { translated ->
                        shownLog.appendText("${LocalDateTime.now()}\t${if (translated.isFinal) "final" else "draft"}\t${translated.id}\t${translated.sentenceEnglish}\t${translated.sentenceChinese}\n")
                        mutableState.update {
                            val cues = if (translated.isFinal) foldTranslation(it.cues, translated) else it.cues
                            it.copy(cues = cues, previewChinese = translated.chinese, lastTranslateMs = translated.translateMs)
                        }
                    },
                    onUnit = { unit ->
                        shownLog.appendText("${LocalDateTime.now()}\tunit\t${unit.sentenceId}\t${unit.english}\t${unit.chinese}\n")
                        // Each Chinese subtitle joins its own sentence's English.
                        if (settings.value.style.mode != SubtitleMode.English) updateCaption { caption.chinese(unit.sentenceId, unit.id, subtitlePunctuation(unit.chinese), it) }
                    },
                    onSentenceUnits = { sentence -> updateCaption { caption.finished(sentence, it) } },
                    onResumed = {
                        clear()
                        mutableState.update { it.copy(previewText = "", previewChinese = "") }
                    },
                    onGap = { gapMs, level -> File(AppPaths.logs, "playback-gaps.log").appendText("${LocalDateTime.now()} gap=${gapMs}ms levelBefore=$level\n") },
                    onLine = { line ->
                        shownLog.appendText("${LocalDateTime.now()}\tline\t${line.sentenceId}\t${line.english}\n")
                        if (!line.cut) {
                            mutableState.update { it.copy(previewText = line.english) }
                            updateCaption { caption.english(line.sentenceId, line.english, it) }
                        }
                    },
                    onCue = { current ->
                        mutableState.update { it.copy(
                            cues = if (current.isFinal) it.cues + current else it.cues,
                            previewCount = it.previewCount + if (current.isFinal) 0 else 1,
                            lastDecodeMs = current.decodeMs, lastQueueMs = current.queueMs, lastLatencyMs = current.latencyMs) }
                        expiry?.let { scheduleExpiry(mutableCue.value) }
                    })
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { report(e) }
            finally {
                source?.stop(); source = null
                detector?.close(); recognizer?.close(); translator?.close()
                mutableState.update { it.copy(running = false, busy = false, stopping = false, level = 0f, queued = 0, previewText = "", previewChinese = "",
                    status = if (it.error == null) "已停止" else "需要处理") }
            }
        }
    }
    fun stop() {
        if (state.value.stopping) return
        mutableState.update { it.copy(stopping = true, status = "正在完成最后一句…") }
        source?.stop()
    }
    fun exportCues(format: ExportFormat) {
        val dialog = FileDialog(null as Frame?, "导出本次字幕", FileDialog.SAVE)
        dialog.file = "OpenSub-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "." + format.extension
        dialog.isVisible = true
        val name = dialog.file ?: return
        val target = File(dialog.directory, if (name.endsWith("." + format.extension, true)) name else "$name.${format.extension}")
        try { target.writeBytes(exportSubtitles(state.value.cues, settings.value.style, format)) } catch (e: Exception) { report(e) }
    }
    fun updateStyle(style: SubtitleStyle) { mutableSettings.update { it.copy(style = style) } }
    fun updatePosition(x: Int, y: Int) { mutableSettings.update { it.copy(overlayX = x, overlayY = y) } }
    override fun show(cue: SubtitleCue) {
        expiry?.cancel()
        mutableCue.value = cue
        scheduleExpiry(cue)
    }
    /** Applies [change] to the sentence on screen, shows the result, and wakes up again when the next sentence's turn comes. */
    private fun updateCaption(change: (nowMs: Long) -> SentenceCaption.Shown): Unit = synchronized(captionLock) {
        val nowMs = captionClock.elapsedNow().inWholeMilliseconds
        val shown = change(nowMs)
        if (shown.sentence >= 0 && (shown.sentence != mutableCue.value?.id || shown.english != mutableCue.value?.english || shown.chinese != mutableChinese.value)) {
            mutableChinese.value = shown.chinese
            show(SubtitleCue(shown.sentence, 0, 0, shown.english, 0))
        }
        nextTurn?.cancel()
        nextTurn = caption.waitMs(nowMs)?.let { waitMs ->
            scope.launch {
                delay(waitMs)
                updateCaption { caption.advance(it) }
            }
        }
    }
    private fun scheduleExpiry(cue: SubtitleCue?) {
        expiry?.cancel()
        expiry = scope.launch {
            delay(7000)
            if (mutableCue.compareAndSet(cue, null)) {
                mutableChinese.value = ""
                forgetCaption()
            }
        }
    }
    private fun forgetCaption() = synchronized(captionLock) {
        nextTurn?.cancel()
        caption.clear()
    }
    override fun clear() {
        expiry?.cancel()
        mutableCue.value = null
        mutableChinese.value = ""
        forgetCaption()
    }
    fun close() { source?.stop(); session?.cancel(); saveSettings(settings.value); scope.cancel() }
    private fun report(e: Throwable) {
        File(AppPaths.logs, "last-error.txt").writeText(e.stackTraceToString())
        mutableState.update { it.copy(error = e.message ?: e.javaClass.simpleName, status = "需要处理") }
    }
}

private fun loadSettings(): DesktopSettings {
    if (!AppPaths.settings.isFile) return DesktopSettings()
    val properties = Properties().apply { AppPaths.settings.inputStream().use { load(it) } }
    return DesktopSettings(SubtitleStyle(properties.getProperty("fontSize")?.toFloatOrNull()?.coerceIn(20f, 64f) ?: 32f,
        properties.getProperty("opacity")?.toFloatOrNull()?.coerceIn(0.15f, 1f) ?: 0.75f,
        SubtitleMode.entries.find { it.name == properties.getProperty("mode") } ?: SubtitleMode.Bilingual,
        SubtitleFont.entries.find { it.name == properties.getProperty("font") } ?: SubtitleFont.Sans,
        properties.getProperty("chineseFirst")?.toBooleanStrictOrNull() ?: false,
        properties.getProperty("englishFontSize")?.toFloatOrNull()?.coerceIn(20f, 64f) ?: 24f),
        properties.getProperty("overlayX")?.toIntOrNull(), properties.getProperty("overlayY")?.toIntOrNull())
}
private fun saveSettings(settings: DesktopSettings) {
    Properties().apply {
        setProperty("fontSize", settings.style.fontSize.toString()); setProperty("opacity", settings.style.backgroundOpacity.toString())
        setProperty("mode", settings.style.mode.name); setProperty("font", settings.style.font.name)
        setProperty("chineseFirst", settings.style.chineseFirst.toString()); setProperty("englishFontSize", settings.style.englishFontSize.toString())
        settings.overlayX?.let { setProperty("overlayX", it.toString()) }; settings.overlayY?.let { setProperty("overlayY", it.toString()) }
        AppPaths.settings.outputStream().use { store(it, "OpenSub subtitle window") }
    }
}
