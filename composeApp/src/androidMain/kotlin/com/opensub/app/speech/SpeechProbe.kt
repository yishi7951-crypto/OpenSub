package com.opensub.app.speech

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import com.opensub.app.core.*
import com.opensub.app.display.AndroidSubtitleOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import kotlin.system.measureTimeMillis

data class SpeechProbeState(val running: Boolean = false, val text: String = "", val status: String = "可离线检查本机识别和翻译模型")

object SpeechProbe {
    private val mutable = MutableStateFlow(SpeechProbeState())
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Debug-only endurance run, without capture or a window: the real pipeline fed a looped clip at playback speed. */
    fun soak(context: Context, minutes: Int, withOverlay: Boolean = false) {
        scope.launch {
            val log = File(context.filesDir, "soak-test.txt").apply { writeText("") }
            val detector = androidSpeechDetector(context)
            val recognizer = AndroidParakeetRecognizer(context)
            // The cache stays off: a looped clip would otherwise stop exercising the translator.
            val translator = androidTranslator(context)
            // A real speech file pushed for testing gives far more honest timings than one looped sentence.
            val speech = File(context.filesDir, "soak.wav")
            val clip = readMono16kWav(if (speech.isFile) speech.readBytes() else context.assets.open("qa/0.wav").use { it.readBytes() }) + FloatArray(SAMPLE_RATE)
            val started = SystemClock.elapsedRealtime()
            val stats = SoakStats { SystemClock.elapsedRealtime() - started }
            var nextReport = 0L
            fun sample() {
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed < nextReport) return
                nextReport += 60_000
                log.appendText("SOAK ${elapsed / 60_000}min ${stats.summary()} pssMb=${Debug.getPss() / 1024}\n")
            }
            val overlay = if (withOverlay) AndroidSubtitleOverlay(context) { log.appendText("OVERLAY ERROR ${it.message}\n") } else null
            val pager = CaptionPager()
            try {
                CaptionPipeline().run(PacedLoopSource(clip, minutes * 60_000L), detector, recognizer, translator = translator,
                    onFrame = { sample() }, onQueue = stats::queue,
                    onTranslation = { stats.translation(it); overlay?.showTranslation(it) },
                    onCue = { cue ->
                        stats.cue(cue)
                        overlay?.let { pager.maxLineChars = it.englishLineChars(); pager.accept(cue)?.let(it::show) }
                    })
                overlay?.close()
                log.appendText("SOAK PASSED minutes=$minutes ${stats.summary()} pssMb=${Debug.getPss() / 1024}\n")
            } catch (e: Throwable) {
                log.appendText("SOAK FAILED ${e.stackTraceToString()}\n")
            } finally { detector.close(); recognizer.close(); translator.close() }
        }
    }
    /**
     * Debug-only, like Windows' recognitionReplay: `files/rolling.wav` once at playback speed through the rolling window, logged
     * to `files/rolling-replay.jsonl` in the same format, so the same scripts read both. [threads] is the recognizer's and [translatorThreads] the translator's;
     * [withOverlay] puts the sentences up in the real subtitle overlay. Times count from when the audio starts.
     */
    fun rolling(context: Context, threads: Int, withOverlay: Boolean, translatorThreads: Int, guessAhead: Boolean) {
        scope.launch {
            val log = ReplayLog(File(context.filesDir, "rolling-replay.jsonl"))
            val recognizer = log.timed(AndroidParakeetRecognizer(context, threads))
            val detector = androidRollingVad(context)
            // No translator threads: recognition alone, to time the translator run by hand beside it.
            val translator = if (translatorThreads > 0) CachingTranslator(log.timed(androidTranslator(context, translatorThreads))) else null
            val clip = readMono16kWav(File(context.filesDir, "rolling.wav").readBytes()) + FloatArray(SAMPLE_RATE)
            val overlay = if (withOverlay) AndroidSubtitleOverlay(context) { log.line("overlayError") } else null
            val caption = SentenceCaption({ overlay?.englishRowChars() ?: 42 }, { overlay?.chineseRowChars() ?: 22 })
            var shown = SentenceCaption.Shown()
            fun showRows(change: (nowMs: Long) -> SentenceCaption.Shown) = synchronized(caption) {
                val rows = change(log.now())
                if (rows != shown) {
                    shown = rows
                    log.rows(rows)
                    overlay?.showSentence(rows.sentence, rows.english, rows.chinese)
                }
            }
            log.start()
            val ticker = launch { while (true) { delay(20); showRows { caption.advance(it) } } }
            try {
                RollingCaptionPipeline(guessAhead = guessAhead).run(PacedLoopSource(clip, clip.size * 1000L / SAMPLE_RATE), detector, recognizer, translator = translator,
                    onUnit = { log.record("unit", it); showRows { now -> caption.chinese(it.sentenceId, it.id, subtitlePunctuation(it.chinese), now) } },
                    onSentenceUnits = { sentence -> showRows { caption.finished(sentence, it) } },
                    onLine = { cue -> log.record("display", cue); if (!cue.cut) showRows { caption.english(cue.sentenceId, cue.english, it) } },
                    onTranslation = { log.record("translation", it) },
                    onCue = { log.record("recognition", it) })
                log.line("done")
            } catch (e: Throwable) {
                log.line("failed", "\"error\":${JSONObject.quote(e.stackTraceToString())}")
            } finally { ticker.cancel(); overlay?.close(); detector.close(); recognizer.close(); translator?.close() }
        }
    }
    fun run(context: Context) {
        if (mutable.value.running) return
        mutable.value = SpeechProbeState(running = true, status = "正在加载本机模型…")
        scope.launch {
            var recognizer: AndroidParakeetRecognizer? = null
            var detector: SpeechDetector? = null
            var translator: Translator? = null
            val report = JSONObject()
            try {
                val bytes = context.assets.open("qa/0.wav").use { it.readBytes() }
                val pcm = readMono16kWav(bytes)
                val loadMs = measureTimeMillis { recognizer = AndroidParakeetRecognizer(context); detector = androidSpeechDetector(context) }
                var text = ""
                val decodeMs = measureTimeMillis { text = recognizer!!.recognize(Utterance(pcm, 0)).trim() }
                check(text.contains("nightfall", true) && text.contains("yellow lamps", true)) { "英文样本识别内容不符合预期：$text" }
                var segments = 0
                for (start in pcm.indices step 320) segments += detector!!.accept(PcmFrame(pcm.copyOfRange(start, minOf(start + 320, pcm.size)), start.toLong())).size
                segments += detector!!.flush().size
                check(segments > 0) { "语音切分未得到有效语段" }
                val translatorLoadMs = measureTimeMillis { translator = androidTranslator(context) }
                val timings = ArrayList<Long>()
                var chinese = ""
                // The first run warms caches, so one sentence alone would overstate the typical time.
                repeat(3) { timings += measureTimeMillis { chinese = translator!!.translate(text) } }
                check(chinese.any { it.code in 0x4E00..0x9FFF }) { "翻译结果不含中文：$chinese" }
                report.put("passed", true).put("loadMs", loadMs).put("decodeMs", decodeMs).put("segments", segments).put("text", text)
                    .put("translatorLoadMs", translatorLoadMs).put("translateMs", org.json.JSONArray(timings)).put("chinese", chinese)
                mutable.value = SpeechProbeState(text = "$text\n$chinese", status = "自检通过：识别 ${decodeMs}ms，翻译 ${timings.last()}ms")
            } catch (e: Throwable) {
                report.put("passed", false).put("error", e.stackTraceToString())
                mutable.value = SpeechProbeState(status = "识别检查失败：${e.message}")
            } finally {
                detector?.close(); recognizer?.close(); translator?.close()
                File(context.filesDir, "speech-self-test.json").writeText(report.toString(2))
            }
        }
    }
}

fun readMono16kWav(bytes: ByteArray): FloatArray {
    fun int(offset: Int) = (0..3).fold(0) { result, index -> result or ((bytes[offset + index].toInt() and 255) shl (index * 8)) }
    fun short(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
    var offset = 12
    while (offset + 8 <= bytes.size) {
        val size = int(offset + 4)
        when (String(bytes, offset, 4)) {
            "fmt " -> require(short(offset + 8) == 1 && short(offset + 10) == 1 && int(offset + 12) == 16000 && short(offset + 22) == 16)
            "data" -> return FloatArray(size / 2) { index -> short(offset + 8 + index * 2).toShort() / 32768f }
        }
        offset += 8 + size + size % 2
    }
    error("WAV file has no PCM data")
}
