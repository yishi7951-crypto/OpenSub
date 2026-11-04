package com.opensub.app.capture

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import com.opensub.app.MainActivity
import com.opensub.app.core.SAMPLE_RATE
import com.opensub.app.core.CachingTranslator
import com.opensub.app.core.RollingCaptionPipeline
import com.opensub.app.core.SentenceCaption
import com.opensub.app.core.SpeechRecognizer
import com.opensub.app.core.SubtitleMode
import com.opensub.app.core.Translator
import com.opensub.app.core.VadEngine
import com.opensub.app.core.foldTranslation
import com.opensub.app.core.subtitlePunctuation
import com.opensub.app.display.AndroidSubtitleOverlay
import com.opensub.app.display.SubtitlePreferences
import com.opensub.app.speech.AndroidParakeetRecognizer
import com.opensub.app.speech.ReplayLog
import com.opensub.app.speech.WavRecorder
import com.opensub.app.speech.androidRollingVad
import com.opensub.app.speech.androidTranslator
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.sqrt

class PlaybackCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var source: AndroidPlaybackAudioSource? = null
    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private var overlay: AndroidSubtitleOverlay? = null
    private var expiry: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "字幕运行状态", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { source?.stop(); if (job?.isActive != true) stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STYLE) { overlay?.updateStyle(SubtitlePreferences.read(this)); if (job?.isActive != true) stopSelf(); return START_NOT_STICKY }
        if (intent == null || job?.isActive == true) return START_NOT_STICKY
        val consent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_CONSENT)
        checkNotNull(consent) { "Missing user-approved MediaProjection token" }
        // Android 14+ requires this foreground service before getMediaProjection().
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        overlay = AndroidSubtitleOverlay(this) { error ->
            CaptureState.update { it.copy(error = error.message) }; source?.stop()
        }
        CaptureState.update { it.copy(recording = true, recordedMs = 0, error = null, level = 0f, cues = emptyList(),
            previewText = "", previewChinese = "", previewCount = 0, status = "正在加载本机字幕模型…") }
        job = scope.launch {
            var total = 0L
            var revoked = false
            var detector: VadEngine? = null
            var recognizer: SpeechRecognizer? = null
            var translator: Translator? = null
            // Each run is logged like a replay, and a debug build keeps its sound too, so a problem on a real video can be replayed.
            val log = ReplayLog(File(filesDir, "live-captions.jsonl"))
            val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            val sound = if (debuggable) WavRecorder(File(filesDir, "live-audio.wav")) else null
            try {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val captureProjection = checkNotNull(manager.getMediaProjection(Activity.RESULT_OK, consent)) { "系统未提供播放捕获会话，请重新授权。" }
                projection = captureProjection
                val audio = AndroidPlaybackAudioSource(captureProjection, null)
                source = audio
                val stopCallback = object : MediaProjection.Callback() {
                    override fun onStop() { revoked = true; audio.stop() }
                }
                callback = stopCallback
                captureProjection.registerCallback(stopCallback, Handler(Looper.getMainLooper()))
                var lastUpdate = 0L
                CaptureState.persist(this@PlaybackCaptureService)
                fun updateFrame(frame: com.opensub.app.core.PcmFrame) {
                    sound?.write(frame.samples)
                    total += frame.samples.size
                    val elapsed = total * 1000 / SAMPLE_RATE
                    if (elapsed - lastUpdate >= 100) {
                        val rms = sqrt(frame.samples.sumOf { (it * it).toDouble() } / frame.samples.size).toFloat()
                        CaptureState.update { it.copy(recordedMs = elapsed, level = rms) }
                        if (elapsed / 1000 != lastUpdate / 1000) { CaptureState.persist(this@PlaybackCaptureService); logLoad(log, elapsed) }
                        lastUpdate = elapsed
                    }
                }
                // The rolling window and one sentence at a time with its own Chinese, as on Windows. Two recognizer threads:
                // four read no faster on this tablet and made translating twice as slow.
                detector = androidRollingVad(this@PlaybackCaptureService)
                recognizer = log.timed(AndroidParakeetRecognizer(this@PlaybackCaptureService))
                translator = CachingTranslator(log.timed(androidTranslator(this@PlaybackCaptureService)))
                CaptureState.update { it.copy(status = "正在聆听平板播放的英语") }
                val caption = SentenceCaption({ overlay?.englishRowChars() ?: 42 }, { overlay?.chineseRowChars() ?: 22 },
                    { SubtitlePreferences.read(this@PlaybackCaptureService).mode != SubtitleMode.English })
                log.start()
                val clock = SystemClock.elapsedRealtime()
                var nextTurn: Job? = null
                var shown = SentenceCaption.Shown()
                /** Applies [change] to the sentence on screen, shows the result, and wakes up again when the next sentence's turn comes. */
                fun updateCaption(change: (nowMs: Long) -> SentenceCaption.Shown): Unit = synchronized(caption) {
                    val nowMs = SystemClock.elapsedRealtime() - clock
                    val rows = change(nowMs)
                    if (rows.sentence >= 0 && rows != shown) {
                        shown = rows
                        log.rows(rows)
                        overlay?.showSentence(rows.sentence, rows.english, rows.chinese)
                        expiry?.cancel()
                        expiry = scope.launch { delay(7000); synchronized(caption) { nextTurn?.cancel(); caption.clear(); shown = SentenceCaption.Shown() }; overlay?.clear() }
                    }
                    nextTurn?.cancel()
                    nextTurn = caption.waitMs(nowMs)?.let { waitMs -> scope.launch { delay(waitMs); updateCaption { caption.advance(it) } } }
                }
                // No translating ahead: a guess holds the translator for a whole request, and on the tablet the real subtitles
                // queue behind it. On the user's YouTube talk the Chinese came 2.8 s after the words without, 3.3 s with.
                RollingCaptionPipeline(guessAhead = false).run(audio, detector, recognizer, translator = translator,
                    onFrame = ::updateFrame,
                    onUnit = { unit -> log.record("unit", unit); updateCaption { caption.chinese(unit.sentenceId, unit.id, subtitlePunctuation(unit.chinese), it) } },
                    onSentenceUnits = { sentence -> updateCaption { caption.finished(sentence, it) } },
                    onResumed = {
                        log.line("resumed")
                        synchronized(caption) { nextTurn?.cancel(); caption.clear(); shown = SentenceCaption.Shown() }
                        overlay?.clear()
                    },
                    onLine = { line ->
                        log.record("display", line)
                        if (!line.cut) {
                            CaptureState.update { it.copy(previewText = line.english) }
                            updateCaption { caption.english(line.sentenceId, line.english, it) }
                        }
                    },
                    onTranslation = { translated ->
                        log.record("translation", translated)
                        CaptureState.update {
                            val cues = if (translated.isFinal) foldTranslation(it.cues, translated) else it.cues
                            it.copy(cues = cues, previewChinese = translated.chinese, lastTranslateMs = translated.translateMs)
                        }
                    },
                    onCue = { current ->
                        log.record("recognition", current)
                        CaptureState.update { it.copy(cues = if (current.isFinal) it.cues + current else it.cues,
                            previewCount = it.previewCount + if (current.isFinal) 0 else 1, lastDecodeMs = current.decodeMs) }
                    })
                CaptureState.update { it.copy(recording = false, level = 0f, previewText = "", previewChinese = "", queued = 0,
                    status = if (revoked) "系统捕获授权已结束，字幕已停止" else "字幕已停止") }
            } catch (e: CancellationException) {
                CaptureState.update { it.copy(recording = false, level = 0f, status = "已中断") }
                throw e
            } catch (e: Throwable) {
                log.line("failed", "\"error\":${org.json.JSONObject.quote(e.stackTraceToString())}")
                File(filesDir, "capture-error.txt").writeText(e.stackTraceToString())
                CaptureState.update { it.copy(recording = false, level = 0f, error = e.message ?: e.javaClass.simpleName, status = "本次处理未完成") }
            } finally {
                log.line("done")
                sound?.close()
                source?.stop(); source = null
                detector?.close(); recognizer?.close(); translator?.close()
                expiry?.cancel(); expiry = null; overlay?.close(); overlay = null
                callback?.let { projection?.unregisterCallback(it) }; callback = null
                projection?.stop(); projection = null
                CaptureState.persist(this@PlaybackCaptureService)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    /** Once a second: the memory the system still has and the CPU this app has used, its own threads only (the translator is another process). */
    private fun logLoad(log: ReplayLog, elapsedMs: Long) {
        val available = runCatching { File("/proc/meminfo").useLines { lines -> lines.first { it.startsWith("MemAvailable") }.filter(Char::isDigit) } }.getOrDefault("0")
        val ticks = runCatching { File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ').let { it[11].toLong() + it[12].toLong() } }.getOrDefault(0)
        log.line("load", "\"audioMs\":$elapsedMs,\"memAvailableKb\":$available,\"cpuTicks\":$ticks")
    }
    override fun onDestroy() { source?.stop(); overlay?.close(); scope.cancel(); super.onDestroy() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); overlay?.resize() }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("OpenSub 字幕正在运行").setContentText("本机离线识别和翻译系统播放声音；点击停止可结束")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }
    companion object {
        const val EXTRA_CONSENT = "consent"
        const val ACTION_STOP = "com.opensub.app.STOP_CAPTURE"
        const val ACTION_STYLE = "com.opensub.app.UPDATE_STYLE"
        private const val CHANNEL = "opensub.capture"
        private const val NOTIFICATION = 1
    }
}
