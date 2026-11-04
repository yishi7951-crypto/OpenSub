package com.opensub.app.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.math.sqrt
import kotlin.time.TimeSource

/** The longest sentence and the speech before it, for its second reading. */
private const val WINDOW_CAPACITY_SECONDS = 60
/** Sound held at this level, after gain, for [SOUND_HOLD_SAMPLES] is read even if the detector hears no speech: it is trained on speech and misses singing over a band. */
private const val SOUND_LEVEL = 0.05f
private const val SOUND_HOLD_SAMPLES = SAMPLE_RATE
/** Silence before a whole sentence is translated: that can take two seconds, and the next subtitle cannot wait for it. */
private const val WHOLE_QUIET_SAMPLES = SAMPLE_RATE * 4

private fun Utterance.upTo(endSample: Long): Utterance {
    val count = (endSample - startSample).coerceIn(0, samples.size.toLong()).toInt()
    return if (count == samples.size) this else Utterance(samples.copyOf(count), startSample)
}

private suspend fun history(conductor: ChineseConductor, cue: SubtitleCue, onTranslation: (SubtitleCue) -> Unit) {
    val mark = TimeSource.Monotonic.markNow()
    val whole = conductor.whole(cue)
    onTranslation(cue.copy(chinese = whole.chinese, translateMs = mark.elapsedNow().inWholeMilliseconds, sentenceEnglish = cue.english, sentenceChinese = whole.chinese))
}

/**
 * Captions for a recognizer that reports word timing, in three parts that do not know about each other's work:
 * [RollingReader] hears words and reads sentences a second time, [SpokenSentences] decides where sentences end by what
 * was said, and [ChineseConductor] cuts them into subtitles and translates them.
 * [onLine] is the sentence being spoken, word by word, for the English. [onUnit] is one subtitle, English with its Chinese,
 * and [onSentenceUnits] says a sentence has no more of them to come. [onTranslation] is a whole sentence for the history.
 * The end of a sentence comes first, then the units of the one being spoken, and whole sentences wait for a pause, so a
 * busy translator delays the history and never the screen. Nothing is lost.
 * [PlaybackWatch] notices a seek or a stall: the sentence ends there, and what follows starts clean through [onResumed].
 * Readings are conflated: a slow reading skips a step, and the next one simply covers more audio.
 * [guessAhead] translates unsettled words ahead; a slow translator spends the time the real subtitles need on it.
 */
class RollingCaptionPipeline(private val stepSamples: Int = SAMPLE_RATE / 4, private val guessAhead: Boolean = true) {
    private class Reading(val audio: Utterance?, val speaking: Boolean, val endSample: Long)
    private class Draft(val cue: SubtitleCue, val atPause: Boolean)
    private class Whole(val cue: SubtitleCue)
    private class Guess(val cue: SubtitleCue)

    suspend fun run(
        source: AudioSource,
        vad: VadEngine,
        recognizer: SpeechRecognizer,
        onFrame: (PcmFrame) -> Unit = {},
        translator: Translator? = null,
        onTranslation: (SubtitleCue) -> Unit = {},
        onLine: (SubtitleCue) -> Unit = {},
        onUnit: (SubtitleCue) -> Unit = {},
        onSentenceUnits: (sentenceId: Long) -> Unit = {},
        onResumed: () -> Unit = {},
        onGap: (gapMs: Long, levelBefore: Float) -> Unit = { _, _ -> },
        onCue: (SubtitleCue) -> Unit,
    ) = coroutineScope {
        val readings = Channel<Reading>(Channel.CONFLATED)
        val finals = Channel<SubtitleCue>(Channel.UNLIMITED)
        val drafts = Channel<Draft>(Channel.CONFLATED)
        val wholes = Channel<Whole>(Channel.UNLIMITED)
        // Translating ahead takes the translator only when nothing real is waiting.
        val guesses = Channel<Guess>(Channel.CONFLATED)
        // A whole sentence holds the translator for a second or more, which the next subtitle cannot wait for. It takes the long pauses.
        val talking = MutableStateFlow(true)
        val wake = Channel<Unit>(Channel.CONFLATED)
        val closed = MutableStateFlow(0L)
        val keepFrom = MutableStateFlow(0L)
        val rolling = RollingReader(recognizer)
        val audio = PcmWindow(WINDOW_CAPACITY_SECONDS * SAMPLE_RATE)
        val autoGain = AutoGain()
        val playback = PlaybackWatch()
        val breakAt = MutableStateFlow(-1L)
        val handledBreak = MutableStateFlow(-1L)
        val started = TimeSource.Monotonic.markNow()

        val translating = launch {
            val conductor = translator?.let { ChineseConductor(it) } ?: return@launch
            var unitId = 0L
            fun show(cue: SubtitleCue, unit: ChineseConductor.Shown, ms: Long) = onUnit(cue.copy(id = ++unitId, sentenceId = cue.id, english = unit.english, isFinal = false,
                chinese = unit.chinese, chineseLine = unit.chinese, translateMs = ms))
            var open = true
            while (open) {
                // select is biased, so the order here is the order of priority.
                val next = select<Any?> {
                    finals.onReceiveCatching { result ->
                        if (result.isClosed) open = false
                        result.getOrNull()
                    }
                    drafts.onReceive { it }
                    guesses.onReceive { it }
                    wake.onReceive { null }
                    if (!talking.value) wholes.onReceive { it }
                } ?: continue
                val mark = TimeSource.Monotonic.markNow()
                when (next) {
                    is SubtitleCue -> {
                        // A sentence cut by a stop still shows its last words: a 40 ms dropout in the audio counts as a stop, and in a
                        // replay of the user's recording that lost "for me as I've been using" and three other endings.
                        conductor.rest(next)?.let { show(next, it, mark.elapsedNow().inWholeMilliseconds) }
                        onSentenceUnits(next.id)
                        // The history has this to show until the whole translation takes its place.
                        if (conductor.sentenceChinese.isNotEmpty()) onTranslation(next.copy(chinese = conductor.sentenceChinese, sentenceEnglish = next.english, sentenceChinese = conductor.sentenceChinese))
                        if (next.cut) conductor.startOver()
                        wholes.trySend(Whole(next))
                    }
                    is Draft -> if (next.cue.id > closed.value) {
                        if (next.atPause) conductor.atPause(next.cue)?.let { show(next.cue, it, mark.elapsedNow().inWholeMilliseconds) }
                        else conductor.units(next.cue) { show(next.cue, it, mark.elapsedNow().inWholeMilliseconds) }
                    }
                    is Whole -> history(conductor, next.cue, onTranslation)
                    is Guess -> if (next.cue.id > closed.value) conductor.guess(next.cue)
                }
            }
            wholes.close()
            for (whole in wholes) history(conductor, whole.cue, onTranslation)
        }

        val reader = launch {
            fun publish(cues: List<SubtitleCue>, decodeMs: Long) {
                for (cue in cues) {
                    val shown = cue.copy(decodeMs = decodeMs, latencyMs = (started.elapsedNow().inWholeMilliseconds - cue.endMs).coerceAtLeast(0))
                    onCue(shown)
                    if (!shown.isFinal) continue
                    onLine(shown.copy(sentenceId = shown.id))
                    if (translator == null) continue
                    closed.value = shown.id
                    finals.trySend(shown)
                }
            }

            for (reading in readings) {
                val mark = TimeSource.Monotonic.markNow()
                val stoppedAt = breakAt.value
                if (stoppedAt > handledBreak.value) {
                    handledBreak.value = stoppedAt
                    publish(rolling.interrupt(reading.audio?.upTo(stoppedAt), stoppedAt).cues, mark.elapsedNow().inWholeMilliseconds)
                    keepFrom.value = (rolling.keepFromSeconds * SAMPLE_RATE).toLong()
                    continue
                }
                val step = rolling.step(reading.audio, reading.speaking, reading.endSample)
                keepFrom.value = (rolling.keepFromSeconds * SAMPLE_RATE).toLong()
                if (step == null) continue
                publish(step.cues, mark.elapsedNow().inWholeMilliseconds)
                // The line only ever shows the words as they were heard: a second reading can have a different number of them.
                step.live?.let { onLine(it.copy(sentenceId = it.id)) }
                if (translator != null) (step.stoppedAt ?: step.live)?.let { drafts.trySend(Draft(it, step.stoppedAt != null)) }
                if (translator != null && guessAhead) step.guess?.let { guesses.trySend(Guess(it)) }
            }
            publish(rolling.finish().cues, 0)
        }

        var lastReadSample = 0L
        var loudSamples = 0
        var lastSpokenSample = 0L
        var speaking = false
        fun offer(endSample: Long, nowSpeaking: Boolean) {
            val clip = if (nowSpeaking || speaking || breakAt.value > handledBreak.value) audio.from(keepFrom.value) else null
            speaking = nowSpeaking
            if (nowSpeaking) lastSpokenSample = endSample
            talking.value = endSample - lastSpokenSample < WHOLE_QUIET_SAMPLES
            if (!talking.value) wake.trySend(Unit)
            readings.trySend(Reading(clip, nowSpeaking, endSample))
        }
        try {
            source.frames().collect { captured ->
                onFrame(captured)
                when (playback.accept(captured)) {
                    PlaybackWatch.Change.Stopped -> {
                        breakAt.value = captured.endSample
                        offer(captured.endSample, false)
                    }
                    PlaybackWatch.Change.Resumed -> onResumed()
                    null -> {}
                }
                if (playback.endedGapMs > 0) onGap(playback.endedGapMs, playback.gapLevelBefore)
                val frame = autoGain.apply(captured)
                val level = sqrt(frame.samples.sumOf { (it * it).toDouble() } / frame.samples.size).toFloat()
                loudSamples = if (level >= SOUND_LEVEL) loudSamples + frame.samples.size else 0
                audio.append(frame)
                vad.accept(frame.samples)
                vad.drain()
                if (frame.endSample - lastReadSample >= stepSamples) {
                    lastReadSample = frame.endSample
                    offer(frame.endSample, vad.speechDetected() || loudSamples >= SOUND_HOLD_SAMPLES)
                }
            }
            vad.flush()
            vad.drain()
            offer(audio.endSample, false)
            readings.close()
            reader.join()
            finals.close()
            translating.join()
            drafts.close()
        } finally {
            readings.cancel()
            finals.cancel()
            drafts.cancel()
            guesses.cancel()
            wholes.cancel()
            reader.cancel()
            translating.cancel()
            source.stop()
        }
    }
}
