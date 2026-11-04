package com.opensub.app

import com.opensub.app.core.*
import com.opensub.app.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.io.File
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/** Explicit developer diagnostics; these never run when the user starts the app normally. */
object Diagnostics {
    suspend fun selfTest() {
        val references = File(AppPaths.sampleAudio, "test_wavs/trans.txt").readLines().filter { it.isNotBlank() }
            .associate { it.substringBefore(' ') to it.substringAfter(' ') }
        val recognizer = ParakeetRecognizer()
        val rows = mutableListOf<String>()
        var punctuated = false
        try {
            for ((name, reference) in references) {
                val samples = WavFiles.readMono16k(File(AppPaths.sampleAudio, "test_wavs/$name"))
                var text = ""
                val elapsed = measureTimeMillis { text = recognizer.recognize(Utterance(samples, 0)) }
                val wer = wordErrorRate(reference, text)
                println("$name: ${elapsed}ms, WER=$wer, $text")
                check(wer < 0.35) { "Sample $name recognition differs too much from the official reference: $wer" }
                punctuated = punctuated || text.any { it in ".?!," }
                rows += "{\"sample\":${json(name)},\"durationMs\":${samples.size * 1000 / SAMPLE_RATE},\"decodeMs\":$elapsed,\"wer\":$wer,\"text\":${json(text)}}"
            }
            // Parakeet leaves some samples unpunctuated, so only a model that never punctuates is a failure.
            check(punctuated) { "Model returned no punctuation for any sample" }
        } finally { recognizer.close() }
        val detector = SherpaDetector()
        try {
            repeat(938) { index -> check(detector.accept(PcmFrame(FloatArray(512), index * 512L)).isEmpty()) }
            check(detector.flush().isEmpty()) { "Silence generated a speech segment" }
        } finally { detector.close() }
        val sample = WavFiles.readMono16k(File(AppPaths.sampleAudio, "test_wavs/0.wav"))
        val tailDetector = SherpaDetector()
        var segments = 0
        try {
            for (start in sample.indices step 320) {
                segments += tailDetector.accept(PcmFrame(sample.copyOfRange(start, minOf(start + 320, sample.size)), start.toLong())).size
            }
            segments += tailDetector.flush().size
            check(segments > 0) { "VAD failed to flush the final sentence" }
        } finally { tailDetector.close() }
        val report = "{\"passed\":true,\"model\":\"Parakeet TDT 110M int8\",\"silenceSeconds\":30,\"tailSegments\":$segments,\"samples\":[${rows.joinToString(",")}] }\n"
        File(AppPaths.logs, "self-test.json").writeText(report)
        println("SELF-TEST PASS — ${AppPaths.logs}")
    }

    suspend fun captureTest(playSample: Boolean) = coroutineScope {
        val samples = File(AppPaths.sampleAudio, "test_wavs/0.wav")
        val rawFile = File(AppPaths.recordings, "qa-original.wav")
        val normalized = File(AppPaths.recordings, "qa-16k.wav")
        var raw: WavWriter? = null
        var mix: MixFormat? = null
        val detector = SherpaDetector()
        val recognizer = ParakeetRecognizer()
        var count = 0L
        var energy = 0.0
        val cues = mutableListOf<SubtitleCue>()
        val audio = WasapiAudioSource(durationSeconds = 10,
            onFormat = { format ->
                if (playSample) check(!format.systemMuted) { "系统播放设备已静音，无法验证测试语音。测试不会自动修改系统音量。" }
                mix = format; println("WASAPI: $format"); raw = WavWriter(rawFile, format)
            },
            onRaw = { _, bytes -> raw!!.append(bytes) })
        val playback = if (playSample) launch(Dispatchers.IO) { delay(1000); WavFiles.play(samples) } else null
        try {
            WavWriter(normalized).use { writer ->
                CaptionPipeline().run(audio, detector, recognizer,
                    onFrame = { frame -> writer.append(frame.samples); count += frame.samples.size; energy += frame.samples.sumOf { (it * it).toDouble() } },
                    onCue = { cue -> if (cue.isFinal) cues += cue; println("CAPTURE ${if (cue.isFinal) "FINAL" else "PREVIEW"} ${cue.startMs}-${cue.endMs}ms (${cue.decodeMs}ms): ${cue.english}") })
            }
            playback?.join()
        } finally { audio.stop(); raw?.close(); detector.close(); recognizer.close(); playback?.cancelAndJoin() }
        val rms = sqrt(energy / count)
        check(count in 159_998L..160_002L) { "Expected 10 seconds of normalized PCM, got $count samples" }
        if (playSample) {
            check(rms > 1.0 / 32768) { "No system playback audio was captured" }
            val text = cues.joinToString(" ") { it.english }
            val reference = File(AppPaths.sampleAudio, "test_wavs/trans.txt").readLines().first().substringAfter(' ')
            check(wordErrorRate(reference, text) < 0.35) { "The played sample was not recognized through system loopback: $text" }
        }
        val rows = cues.joinToString(",") { "{\"startMs\":${it.startMs},\"endMs\":${it.endMs},\"decodeMs\":${it.decodeMs},\"text\":${json(it.english)}}" }
        File(AppPaths.logs, "capture-test.json").writeText("{\"passed\":true,\"sampleCount\":$count,\"rms\":$rms,\"mixFormat\":${json(mix.toString())},\"rawFile\":${json(rawFile.absolutePath)},\"pcmFile\":${json(normalized.absolutePath)},\"cues\":[$rows]}\n")
        println("CAPTURE-TEST PASS — $normalized")
    }

    /** Listens without a window, test playback, recording files, or system volume changes. */
    suspend fun probeAudio() {
        var count = 0L
        var energy = 0.0
        var peak = 0f
        var mix: MixFormat? = null
        var muted = false
        WasapiAudioSource(durationSeconds = 5, onFormat = { mix = it }, onMuteChanged = { muted = it }).frames().collect { frame ->
            count += frame.samples.size
            frame.samples.forEach { value -> energy += value * value; peak = maxOf(peak, kotlin.math.abs(value)) }
        }
        val rms = sqrt(energy / count)
        val report = "{\"sampleCount\":$count,\"systemMuted\":$muted,\"rms\":$rms,\"peak\":$peak,\"format\":${json(mix.toString())}}\n"
        File(AppPaths.logs, "audio-probe.json").writeText(report)
        println(report)
        check(count in 79_998L..80_002L) { "Audio capture clock failed: $count samples" }
    }

    /** Rechecks the previously recorded real loopback file using the same shipped model. */
    suspend fun verifyRecording() {
        val file = File(AppPaths.recordings, "qa-16k.wav")
        val samples = WavFiles.readMono16k(file)
        check(samples.size in 159_998..160_002) { "The recorded loopback file is not ten seconds long" }
        val recognizer = ParakeetRecognizer()
        val text = try { recognizer.recognize(Utterance(samples, 0)) } finally { recognizer.close() }
        val expected = "After early nightfall the yellow lamps would light up here and there the squalid quarter of the brothels"
        val wer = wordErrorRate(expected, text)
        val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
        check(wer < 0.35) { "Recorded speech recognition failed: $text" }
        check(rms > 1.0 / 32768) { "Recorded audio is silent" }
        val report = "{\"passed\":true,\"file\":${json(file.absolutePath)},\"samples\":${samples.size},\"rms\":$rms,\"wer\":$wer,\"text\":${json(text)}}\n"
        File(AppPaths.logs, "recording-check.json").writeText(report)
        println(report)
    }

    /** Real-time paced local samples: does not touch speakers, browser, windows, or mouse. */
    suspend fun latencyBenchmark() {
        val references = File(AppPaths.sampleAudio, "test_wavs/trans.txt").readLines().filter { it.isNotBlank() }
            .associate { it.substringBefore(' ') to it.substringAfter(' ') }
        val rows = mutableListOf<String>()
        for (name in listOf("0.wav", "1.wav")) {
            val samples = WavFiles.readMono16k(File(AppPaths.sampleAudio, "test_wavs/$name")) + FloatArray(SAMPLE_RATE)
            for (fast in listOf(false, true)) {
                val detector = if (fast) SherpaDetector() else SherpaDetector(false, 0.55f, false)
                val recognizer = ParakeetRecognizer()
                val clock = kotlin.time.TimeSource.Monotonic.markNow()
                val events = mutableListOf<Pair<Long, SubtitleCue>>()
                val source = object : AudioSource {
                    override fun frames() = flow {
                        for (start in samples.indices step 320) {
                            val end = minOf(start + 320, samples.size)
                            val due = end * 1000L / SAMPLE_RATE
                            val wait = due - clock.elapsedNow().inWholeMilliseconds
                            if (wait > 0) delay(wait)
                            emit(PcmFrame(samples.copyOfRange(start, end), start.toLong()))
                        }
                    }
                    override fun stop() {}
                }
                try {
                    CaptionPipeline().run(source, detector, recognizer, onCue = { events += clock.elapsedNow().inWholeMilliseconds to it })
                } finally { detector.close(); recognizer.close() }
                val finals = events.filter { it.second.isFinal }
                val previews = events.filterNot { it.second.isFinal }
                check(finals.isNotEmpty()) { "No finals: $name" }
                val text = finals.joinToString(" ") { it.second.english }
                val wer = wordErrorRate(references.getValue(name), text)
                check(wer < 0.35) { "Final accuracy regressed: $name $wer $text" }
                if (fast) {
                    check(previews.isNotEmpty() && events.first().first < 2200) { "Preview did not appear promptly: $name" }
                    check(finals.maxOf { it.second.endMs - it.second.startMs } <= 7000) { "Hard speech limit was exceeded" }
                }
                val mode = if (fast) "preview" else "previous"
                val firstMs = events.first().first
                val maxQueueMs = events.maxOf { it.second.queueMs }
                val meanDecodeMs = events.map { it.second.decodeMs }.average()
                val finalLatencyMs = finals.maxOf { it.second.latencyMs }
                rows += "{\"sample\":${json(name)},\"mode\":${json(mode)},\"firstDisplayMs\":$firstMs,\"previews\":${previews.size},\"finals\":${finals.size},\"maxQueueMs\":$maxQueueMs,\"meanDecodeMs\":$meanDecodeMs,\"maxFinalLatencyMs\":$finalLatencyMs,\"wer\":$wer,\"text\":${json(text)}}"
                println("LATENCY $name $mode: first=${firstMs}ms, previews=${previews.size}, maxQueue=${maxQueueMs}ms, WER=$wer, $text")
            }
        }
        File(AppPaths.logs, "latency-benchmark.json").writeText("{\"passed\":true,\"source\":\"real-time paced local WAV, no speaker playback\",\"cases\":[${rows.joinToString(",")}] }\n")
    }

    private fun json(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    private fun wordErrorRate(reference: String, actual: String): Double {
        fun words(s: String) = s.lowercase().replace(Regex("[^a-z' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val expected = words(reference); val recognized = words(actual)
        var previous = IntArray(recognized.size + 1) { it }
        for (i in expected.indices) {
            val current = IntArray(recognized.size + 1); current[0] = i + 1
            for (j in recognized.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (expected[i] == recognized[j]) 0 else 1)
            previous = current
        }
        return previous.last().toDouble() / expected.size
    }
}
