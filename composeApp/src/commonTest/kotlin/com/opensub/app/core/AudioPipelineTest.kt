package com.opensub.app.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.*

class AudioPipelineTest {
    @Test fun acousticCorrectionsReachEnglishEvenWithoutTranslation() = runTest {
        val cues = ArrayList<SubtitleCue>()
        val source = object : AudioSource {
            override fun frames() = flow { emit(PcmFrame(FloatArray(SAMPLE_RATE), 0)); delay(10); emit(PcmFrame(FloatArray(SAMPLE_RATE), SAMPLE_RATE.toLong())) }
            override fun stop() {}
        }
        val detector = object : SpeechDetector {
            override fun accept(frame: PcmFrame) = listOf(Utterance(frame.samples, if (frame.startSample == 0L) 0 else 17_600))
            override fun flush() = emptyList<Utterance>()
            override fun close() {}
        }
        val recognizer = object : SpeechRecognizer {
            override suspend fun recognize(utterance: Utterance) = if (utterance.startSample == 0L) "First phrase." else "Wrong word."
            override suspend fun words(utterance: Utterance) = listOf(HeardWord("First", 0f), HeardWord("phrase.", 0.5f), HeardWord("Correct", 1.2f), HeardWord("word.", 1.5f))
            override fun close() {}
        }
        CaptionPipeline().run(source, detector, recognizer, onCue = { cues += it })
        assertEquals(listOf("First phrase.", "Correct word."), cues.map { it.english })
    }
    @Test fun previewWindowKeepsRecentSamplesAcrossWrapAndLargePackets() {
        val window = PcmWindow(8)
        window.append(PcmFrame(FloatArray(5) { it.toFloat() }, 0))
        window.append(PcmFrame(FloatArray(7) { (it + 5).toFloat() }, 5))
        assertEquals(4L, window.startSample)
        assertEquals((4..11).map { it.toFloat() }, window.from(0).samples.toList())
        window.append(PcmFrame(FloatArray(20) { (it + 12).toFloat() }, 12))
        assertEquals(24L, window.startSample)
        assertEquals((24..31).map { it.toFloat() }, window.from(0).samples.toList())
    }

    @Test fun previewAppearsBeforeFinalUsingTheSameCaptionId() = runTest {
        val events = previewScenario(previewDecodeMs = 5)
        assertEquals(listOf(false, true), events.map { it.isFinal })
        assertEquals(listOf(1L, 1L), events.map { it.id })
    }

    @Test fun aPreviewAlreadyDecodingCannotOverwriteItsQueuedFinal() = runTest {
        val events = previewScenario(previewDecodeMs = 100)
        assertEquals(1, events.size)
        assertTrue(events.single().isFinal)
        assertEquals("Final sentence.", events.single().english)
    }

    private suspend fun previewScenario(previewDecodeMs: Long): List<SubtitleCue> {
        val events = ArrayList<SubtitleCue>()
        val source = object : AudioSource {
            override fun frames() = flow {
                emit(PcmFrame(FloatArray(20_800), 0))
                delay(20)
                emit(PcmFrame(FloatArray(6400), 20_800))
                delay(20)
                emit(PcmFrame(FloatArray(1600), 27_200))
            }
            override fun stop() {}
        }
        val detector = object : SpeechDetector {
            var finished = false
            override fun accept(frame: PcmFrame): List<Utterance> {
                finished = frame.startSample > 20_800
                return if (finished) listOf(Utterance(FloatArray(22_400), 0)) else emptyList()
            }
            override fun preview() = if (!finished) Utterance(FloatArray(20_800), 0) else null
            override fun flush() = emptyList<Utterance>()
            override fun close() {}
        }
        val recognizer = object : SpeechRecognizer {
            override suspend fun recognize(utterance: Utterance): String {
                val preview = utterance.samples.size == 20_800
                delay(if (preview) previewDecodeMs else 1)
                return if (preview) "Draft" else "Final sentence."
            }
            override fun close() {}
        }
        CaptionPipeline().run(source, detector, recognizer, onCue = { events += it })
        return events
    }

    @Test fun resamplingPreservesRateAcrossIrregularPackets() {
        for (rate in listOf(44_100, 48_000)) {
            val input = FloatArray(rate) { sin(2 * PI * 1000 * it / rate).toFloat() }
            val whole = StreamingResampler(rate).accept(input)
            val converter = StreamingResampler(rate)
            val parts = ArrayList<Float>()
            for (start in input.indices step 317) parts += converter.accept(input.copyOfRange(start, minOf(start + 317, input.size))).toList()
            assertEquals(16_000, whole.size)
            assertEquals(whole.size, parts.size)
            whole.indices.forEach { assertTrue(abs(whole[it] - parts[it]) < 0.00001f) }
        }
    }
    @Test fun downsamplingRejectsAliasingAboveEightKhz() {
        fun rms(frequency: Int): Double {
            val input = FloatArray(48_000) { sin(2 * PI * frequency * it / 48_000).toFloat() }
            val output = StreamingResampler(48_000).accept(input).drop(100)
            return sqrt(output.sumOf { (it * it).toDouble() } / output.size)
        }
        assertTrue(rms(1000) > 0.65)
        assertTrue(rms(11_000) < 0.02)
    }
    @Test fun longUtteranceSplitsWithoutMissingSamplesOrChangingClock() {
        val original = Utterance(FloatArray(17 * SAMPLE_RATE) { it.toFloat() }, 19_200)
        val split = original.split()
        assertEquals(listOf(7, 7, 3).map { it * SAMPLE_RATE }, split.map { it.samples.size })
        assertEquals(original.startSample, split.first().startSample)
        assertEquals(original.endMs, split.last().endMs)
        assertEquals(original.samples.toList(), split.flatMap { it.samples.toList() })
    }
    @Test fun recognitionKeepsOrderAndFlushesTail() = runTest {
        val output = ArrayList<SubtitleCue>()
        val source = object : AudioSource {
            override fun frames() = flow { repeat(3) { emit(PcmFrame(floatArrayOf(it.toFloat()), it.toLong())) } }
            override fun stop() {}
        }
        val detector = object : SpeechDetector {
            override fun accept(frame: PcmFrame) = listOf(Utterance(frame.samples, frame.startSample))
            override fun flush() = listOf(Utterance(floatArrayOf(3f), 3))
            override fun close() {}
        }
        val recognizer = object : SpeechRecognizer {
            override suspend fun recognize(utterance: Utterance): String { delay(10); return "Sentence ${utterance.startSample}." }
            override fun close() {}
        }
        CaptionPipeline().run(source, detector, recognizer, onCue = { output += it })
        assertEquals(listOf("Sentence 0.", "Sentence 1.", "Sentence 2.", "Sentence 3."), output.map { it.english })
        assertEquals(listOf(1L, 2L, 3L, 4L), output.map { it.id })
    }
}
