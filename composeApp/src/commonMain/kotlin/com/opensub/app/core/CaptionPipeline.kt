package com.opensub.app.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Finals retain order and priority. Pending previews are conflated to the newest audio.
 * Chinese has two sources. Finals are grouped into sentences by [SentenceWindow] and each new part retranslates the whole sentence;
 * that queue sheds its oldest entry rather than falling behind the video. While someone is still speaking, settled
 * preview text is translated as a replaceable draft whenever the translator is free, so Chinese need not wait for a pause.
 */
private const val TRANSLATION_BACKLOG = 3
private const val FIRST_DRAFT_WORDS = 4
private const val NEXT_DRAFT_WORDS = 8

class CaptionPipeline(private val previewIntervalSamples: Int = SAMPLE_RATE * 2 / 5) {
    private data class Request(val id: Long, val utterance: Utterance, val isFinal: Boolean, val queuedAt: TimeMark)
    suspend fun run(
        source: AudioSource,
        detector: SpeechDetector,
        recognizer: SpeechRecognizer,
        onFrame: (PcmFrame) -> Unit = {},
        onQueue: (Int) -> Unit = {},
        translator: Translator? = null,
        onTranslation: (SubtitleCue) -> Unit = {},
        onCue: (SubtitleCue) -> Unit,
    ) = coroutineScope {
        val queue = Channel<Request>(4)
        val previews = Channel<Request>(Channel.CONFLATED)
        val translations = Channel<SubtitleCue>(TRANSLATION_BACKLOG, BufferOverflow.DROP_OLDEST)
        val drafts = Channel<SubtitleCue>(Channel.CONFLATED)
        val newestSentence = MutableStateFlow<SubtitleCue?>(null)
        val window = SentenceWindow(recognizer)
        val waiting = MutableStateFlow(0)
        val lastFinalQueued = MutableStateFlow(0L)
        val started = TimeSource.Monotonic.markNow()
        var nextId = 1L
        var lastPreviewSample = 0L
        val stabilizer = PreviewStabilizer()
        var draftedId = 0L
        var draftedWords = 0
        val translating = launch {
            var open = translator != null
            while (open) {
                val cue = select<SubtitleCue?> {
                    // select is biased: a finished sentence takes priority over any draft.
                    translations.onReceiveCatching { result ->
                        if (result.isClosed) open = false
                        result.getOrNull()
                    }
                    drafts.onReceive { it }
                } ?: continue
                val newest = newestSentence.value
                if (cue.isFinal && newest != null && newest.id == cue.id && (newest.endMs != cue.endMs || newest.english != cue.english)) continue
                if (!cue.isFinal && cue.id <= lastFinalQueued.value) continue
                val mark = TimeSource.Monotonic.markNow()
                val chinese = translator!!.translate(cue.english).trim()
                // A sentence may have finished while its draft was being translated.
                if (!cue.isFinal && cue.id <= lastFinalQueued.value) continue
                val updated = newestSentence.value
                if (cue.isFinal && updated != null && updated.id == cue.id && (updated.endMs != cue.endMs || updated.english != cue.english)) continue
                if (chinese.isNotEmpty()) onTranslation(cue.copy(chinese = chinese, translateMs = mark.elapsedNow().inWholeMilliseconds))
            }
        }
        val decoder = launch {
            var finalsOpen = true
            var previewsOpen = true
            while (finalsOpen || previewsOpen) {
                val request = select<Request?> {
                    // select is biased: a waiting final takes priority over any preview.
                    if (finalsOpen) queue.onReceiveCatching { result ->
                        if (result.isClosed) finalsOpen = false
                        result.getOrNull()
                    }
                    if (previewsOpen) previews.onReceiveCatching { result ->
                        if (result.isClosed) previewsOpen = false
                        result.getOrNull()
                    }
                } ?: continue
                if (request.isFinal) {
                    waiting.update { it - 1 }
                    onQueue(waiting.value)
                } else if (request.id <= lastFinalQueued.value) continue
                val waitMs = request.queuedAt.elapsedNow().inWholeMilliseconds
                val mark = TimeSource.Monotonic.markNow()
                val utterance = request.utterance
                val heard = utterance.sentenceText(recognizer.recognize(utterance))
                // A final may have arrived while its preview was decoding. Never show that stale preview.
                if (!request.isFinal && request.id <= lastFinalQueued.value) continue
                val text = if (request.isFinal) heard else stabilizer.accept(request.id, heard) ?: continue
                if (text.isNotEmpty()) {
                    val cue = SubtitleCue(request.id, utterance.startMs, utterance.endMs, text,
                        mark.elapsedNow().inWholeMilliseconds, request.isFinal, waitMs,
                        (started.elapsedNow().inWholeMilliseconds - utterance.endMs).coerceAtLeast(0))
                    // Acoustic corrections also belong on the English screen, including in English-only diagnostics.
                    val sentences = if (cue.isFinal) window.add(cue, utterance) else emptyList()
                    onCue(if (cue.isFinal) cue.copy(english = window.latestEnglish, decodeMs = mark.elapsedNow().inWholeMilliseconds,
                        latencyMs = (started.elapsedNow().inWholeMilliseconds - utterance.endMs).coerceAtLeast(0)) else cue)
                    if (translator != null && cue.isFinal) {
                        for (sentence in sentences) {
                            newestSentence.value = sentence
                            translations.trySend(sentence)
                        }
                    } else if (translator != null) {
                        val words = text.split(' ').size
                        if (cue.id != draftedId) { draftedId = cue.id; draftedWords = 0 }
                        // The first draft is about speed; after that, replacing Chinese too often is distracting.
                        if (words - draftedWords >= (if (draftedWords == 0) FIRST_DRAFT_WORDS else NEXT_DRAFT_WORDS)) {
                            draftedWords = words
                            drafts.trySend(window.peek(cue).copy(id = cue.id, startMs = cue.startMs, isFinal = false))
                        }
                    }
                }
            }
        }
        fun enqueue(utterances: List<Utterance>) {
            for (utterance in utterances.flatMap { it.split() }) {
                val id = nextId++
                lastFinalQueued.value = id
                waiting.update { it + 1 }
                check(queue.trySend(Request(id, utterance, true, TimeSource.Monotonic.markNow())).isSuccess) { "识别速度跟不上播放，待识别语句已达上限。请停止后重试，或减少其他程序的 CPU 占用。" }
                onQueue(waiting.value)
            }
        }
        try {
            source.frames().collect { frame ->
                onFrame(frame)
                enqueue(detector.accept(frame))
                if (frame.endSample - lastPreviewSample >= previewIntervalSamples && waiting.value == 0) {
                    detector.preview()?.let { utterance ->
                        previews.trySend(Request(nextId, utterance, false, TimeSource.Monotonic.markNow()))
                        lastPreviewSample = frame.endSample
                    }
                }
            }
            enqueue(detector.flush())
            queue.close()
            previews.close()
            decoder.join()
            translations.close()
            translating.join()
            drafts.close()
        } finally {
            queue.cancel()
            previews.cancel()
            translations.cancel()
            drafts.cancel()
            decoder.cancel()
            translating.cancel()
            source.stop()
        }
    }
}
