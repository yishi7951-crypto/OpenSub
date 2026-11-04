package com.opensub.app.core

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class WordGapTest {
    /** Loud tone, with each range in seconds turned almost silent. */
    private fun speech(seconds: Double, vararg dips: ClosedFloatingPointRange<Double>) = FloatArray((seconds * SAMPLE_RATE).toInt()) { index ->
        val time = index.toDouble() / SAMPLE_RATE
        val amplitude = if (dips.any { time in it }) 0.01 else 0.5
        (amplitude * sin(2 * PI * 200 * time)).toFloat()
    }

    private fun assertSampleIn(range: ClosedFloatingPointRange<Double>, sample: Long) {
        val seconds = sample.toDouble() / SAMPLE_RATE
        assertTrue(seconds in range, "$seconds is outside $range")
    }

    @Test fun cutsInTheLongestDip() {
        val gap = findWordGap(speech(7.0, 3.0..3.1, 5.0..5.3), 2 * SAMPLE_RATE)
        assertSampleIn(5.0..5.3, gap.toLong())
    }

    @Test fun ignoresDipsBeforeTheShortestPiece() {
        val gap = findWordGap(speech(7.0, 1.0..1.5, 4.0..4.1), 2 * SAMPLE_RATE)
        assertSampleIn(4.0..4.1, gap.toLong())
    }

    @Test fun equalDipsKeepThePieceLong() {
        val gap = findWordGap(speech(7.0, 3.2..3.4, 6.4..6.6), 2 * SAMPLE_RATE)
        assertSampleIn(6.4..6.6, gap.toLong())
    }

    @Test fun speechWithoutADipIsCutAtItsQuietestMoment() {
        val samples = FloatArray(7 * SAMPLE_RATE) { index ->
            val time = index.toDouble() / SAMPLE_RATE
            ((if (time in 4.0..4.05) 0.3 else 0.5) * sin(2 * PI * 200 * time)).toFloat()
        }
        assertSampleIn(3.95..4.1, findWordGap(samples, 2 * SAMPLE_RATE).toLong())
    }

    @Test fun audioShorterThanTheShortestPieceIsNotCut() {
        assertEquals(SAMPLE_RATE, findWordGap(FloatArray(SAMPLE_RATE), 2 * SAMPLE_RATE))
    }

    private class FakeVad : VadEngine {
        var speaking = true
        val finished = ArrayList<Utterance>()
        override fun accept(samples: FloatArray) {}
        override fun speechDetected() = speaking
        override fun drain() = finished.toList().also { finished.clear() }
        override fun flush() {}
        override fun close() {}
    }

    private fun feed(detector: SegmentingSpeechDetector, audio: FloatArray, from: Int = 0, to: Int = audio.size): List<Utterance> {
        val pieces = ArrayList<Utterance>()
        for (start in from until to step 320) {
            val end = minOf(start + 320, to)
            pieces += detector.accept(PcmFrame(audio.copyOfRange(start, end), start.toLong()))
        }
        return pieces
    }

    @Test fun longSpeechIsCutInAWordGapInsteadOfAtTheTimeLimit() {
        val audio = speech(7.5, 5.7..6.0)
        val cut = feed(SegmentingSpeechDetector(FakeVad()), audio).single()
        assertTrue(cut.cutMidSpeech)
        assertTrue(cut.trailingSamples.isNotEmpty())
        assertContentEquals(audio.copyOfRange((cut.startSample + cut.samples.size).toInt(), (cut.startSample + cut.samples.size + cut.trailingSamples.size).toInt()), cut.trailingSamples)
        assertSampleIn(5.7..6.0, cut.startSample + cut.samples.size)
    }

    @Test fun aCutNeverReachesFarBackForALongerGap() {
        val audio = speech(7.5, 3.0..3.5, 6.2..6.3)
        val cut = feed(SegmentingSpeechDetector(FakeVad()), audio).single()
        assertSampleIn(6.2..6.3, cut.startSample + cut.samples.size)
    }

    @Test fun theVadSegmentAfterACutSendsOnlyTheRest() {
        val audio = speech(9.0, 5.0..5.3)
        val vad = FakeVad()
        val detector = SegmentingSpeechDetector(vad)
        val cut = feed(detector, audio, to = 8 * SAMPLE_RATE).single()
        val cutEnd = cut.startSample + cut.samples.size
        vad.speaking = false
        vad.finished += Utterance(audio, 0)
        val rest = feed(detector, audio, from = 8 * SAMPLE_RATE).single()
        assertFalse(rest.cutMidSpeech)
        assertEquals(cutEnd, rest.startSample)
        assertEquals(audio.size.toLong(), rest.startSample + rest.samples.size)
        assertEquals(audio.toList(), (cut.samples + rest.samples).toList().let { audio.take(cut.startSample.toInt()) + it })
    }

    @Test fun evenAShortTailAfterACutIsKeptSoTheLastWordCannotBeLost() {
        val audio = speech(7.0, 6.8..6.95)
        val vad = FakeVad()
        val detector = SegmentingSpeechDetector(vad)
        val cut = feed(detector, audio).single()
        vad.speaking = false
        val end = (cut.startSample + cut.samples.size + SAMPLE_RATE / 8).toInt()
        vad.finished += Utterance(audio.copyOf(end), 0)
        val tail = detector.flush().single()
        assertEquals(cut.startSample + cut.samples.size, tail.startSample)
        assertEquals(end.toLong(), tail.startSample + tail.samples.size)
    }

    @Test fun speechAfterACutPreviewsFromTheCut() {
        val audio = speech(7.5, 3.0..3.3)
        val detector = SegmentingSpeechDetector(FakeVad())
        val cut = feed(detector, audio).single()
        assertEquals(cut.startSample + cut.samples.size, detector.preview()?.startSample)
    }

    @Test fun aCutPieceDropsTheRecognizersFullStopOnly() {
        val cut = Utterance(FloatArray(1), 0, cutMidSpeech = true)
        assertEquals("in a time span", cut.sentenceText(" in a time span. "))
        assertEquals("Is it true?", cut.sentenceText("Is it true?"))
        assertEquals("A whole sentence.", Utterance(FloatArray(1), 0).sentenceText("A whole sentence."))
    }

    @Test fun aCutPieceStaysInOneSentenceWithTheNext() {
        val cut = Utterance(FloatArray(1), 0, cutMidSpeech = true)
        val grouper = SentenceGrouper()
        grouper.add(SubtitleCue(1, 0, 7_000, cut.sentenceText("We have reached."), 0))
        assertEquals("We have reached the stars.", grouper.add(SubtitleCue(2, 7_000, 9_000, "The stars.", 0)).english)
    }

    @Test fun thePipelineTranslatesACutSentenceAsOne() = runTest {
        val audio = speech(9.0, 5.0..5.3)
        val vad = FakeVad()
        val source = object : AudioSource {
            override fun frames() = flow {
                for (start in audio.indices step 320) emit(PcmFrame(audio.copyOfRange(start, minOf(start + 320, audio.size)), start.toLong()))
                vad.speaking = false
                vad.finished += Utterance(audio, 0)
            }
            override fun stop() {}
        }
        val recognizer = object : SpeechRecognizer {
            override suspend fun recognize(utterance: Utterance) = if (utterance.cutMidSpeech) "We have reached." else "The stars."
            override fun close() {}
        }
        val translator = object : Translator {
            override suspend fun translate(english: String) = english
            override fun close() {}
        }
        val cues = ArrayList<SubtitleCue>()
        val translations = ArrayList<String>()
        CaptionPipeline().run(source, SegmentingSpeechDetector(vad, previewsEnabled = false), recognizer, translator = translator,
            onTranslation = { translations += it.chinese }, onCue = { cues += it })
        assertEquals(listOf("We have reached", "The stars."), cues.map { it.english })
        assertEquals("We have reached the stars.", translations.last())
    }

    @Test fun splittingKeepsTheCutMark() {
        val pieces = Utterance(FloatArray(10), 0, cutMidSpeech = true).split(4)
        assertEquals(listOf(true, true, true), pieces.map { it.cutMidSpeech })
        assertEquals(listOf(true, true, false), Utterance(FloatArray(10), 0).split(4).map { it.cutMidSpeech })
    }
}
