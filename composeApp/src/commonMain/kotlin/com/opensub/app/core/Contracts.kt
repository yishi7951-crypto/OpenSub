package com.opensub.app.core

import kotlinx.coroutines.flow.Flow

const val SAMPLE_RATE = 16_000

/** All platforms deliver normalized mono PCM, with a continuous session-local sample clock. */
data class PcmFrame(val samples: FloatArray, val startSample: Long) {
    val endSample: Long get() = startSample + samples.size
}

interface AudioSource {
    fun frames(): Flow<PcmFrame>
    /** Ends capture normally, so the detector can flush the last unfinished sentence. */
    fun stop()
}

data class Utterance(
    val samples: FloatArray, val startSample: Long, val cutMidSpeech: Boolean = false,
    /** Already captured sound beyond a cut, for hearing the complete last word. It belongs to the next piece's clock. */
    val trailingSamples: FloatArray = FloatArray(0),
) {
    val startMs: Long get() = startSample * 1000 / SAMPLE_RATE
    val endMs: Long get() = (startSample + samples.size) * 1000 / SAMPLE_RATE

    /** The recognizer closes every piece with a full stop; a piece cut mid-speech has not ended its sentence. */
    fun sentenceText(heard: String): String = if (cutMidSpeech) heard.trim().trimEnd('.') else heard.trim()

    fun split(maxSamples: Int = 7 * SAMPLE_RATE): List<Utterance> {
        require(maxSamples > 0)
        return (samples.indices step maxSamples).map { start ->
            val end = minOf(start + maxSamples, samples.size)
            val trailing = if (end < samples.size) samples.copyOfRange(end, minOf(end + SAMPLE_RATE * 8 / 25, samples.size)) else trailingSamples
            Utterance(samples.copyOfRange(start, end), startSample + start, cutMidSpeech || end < samples.size, trailing)
        }
    }
}

interface SpeechDetector {
    fun accept(frame: PcmFrame): List<Utterance>
    /** Current speech for replaceable on-screen previews; never added to final history. */
    fun preview(): Utterance? = null
    fun flush(): List<Utterance>
    fun close()
}

/** A recognized word and when it starts, in seconds from the start of the audio it was heard in. */
data class HeardWord(val text: String, val startSeconds: Float, val unsure: Boolean = false)

interface SpeechRecognizer {
    suspend fun recognize(utterance: Utterance): String
    /** The same recognition with word timing. Empty when the recognizer has no timing to report. */
    suspend fun words(utterance: Utterance): List<HeardWord> = emptyList()
    fun close()
}

interface Translator {
    suspend fun translate(english: String): String
    /** Translates [english] so that the result starts with [shown], where the model can; otherwise a plain translation. */
    suspend fun continueFrom(english: String, shown: String): String = translate(english)
    /** Translates [english], with [before] as background the model reads but does not translate. */
    suspend fun translateAfter(english: String, before: String): String = translate(english)
    /** True when [continueFrom] really does carry on from what it is given. */
    val continues: Boolean get() = false
    /** False when [translateAfter] ignores its background: asked again without it, the request would only be repeated. */
    val readsBackground: Boolean get() = true
    fun close()
}

data class SubtitleCue(
    val id: Long, val startMs: Long, val endMs: Long, val english: String, val decodeMs: Long,
    val isFinal: Boolean = true, val queueMs: Long = 0, val latencyMs: Long = 0,
    val chinese: String = "", val translateMs: Long = 0,
    /** What the Chinese line shows now. It follows the speech, not this cue's words. */
    val chineseLine: String = "",
    /** The sentence a translation belongs to, as far as it has gone: lines are only how it was laid out. */
    val sentenceEnglish: String = "", val sentenceChinese: String = "",
    /** Playback stopped during this sentence, so it belongs in the history, and the screen has moved on. */
    val cut: Boolean = false,
    /** The spoken sentence a line or a subtitle is part of. Lines and subtitles number themselves; this says which belong together. */
    val sentenceId: Long = 0,
)

enum class SubtitleMode(val label: String) { Bilingual("双语"), Chinese("中文"), English("英文") }

enum class SubtitleFont(val label: String) { Sans("黑体"), Serif("宋体") }

data class SubtitleStyle(
    val fontSize: Float = 32f, val backgroundOpacity: Float = 0.75f, val mode: SubtitleMode = SubtitleMode.Bilingual,
    val font: SubtitleFont = SubtitleFont.Sans, val chineseFirst: Boolean = false, val englishFontSize: Float = 24f,
)

interface SubtitleDisplay {
    fun show(cue: SubtitleCue)
    fun clear()
}
