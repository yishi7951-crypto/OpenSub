package com.opensub.app

import com.opensub.app.core.*
import com.opensub.app.platform.AppPaths
import com.opensub.app.platform.ParakeetRecognizer
import com.opensub.app.platform.SherpaDetector
import com.opensub.app.platform.WavFiles
import com.opensub.app.platform.desktopTranslator
import com.opensub.app.platform.rollingVad
import java.io.File
import kotlin.system.measureTimeMillis

private val Samples = listOf(
    "We choose to go to the moon.",
    "We choose to go to the moon in this decade and do the other things, not because they are easy, but because they are hard.",
    "The model was trained on a large corpus.",
    "It still has some limitations.",
    "I don't think that's necessarily true.",
    "Thank you very much.",
    "Space is there, and we're going to climb it.",
)

private const val MINUTE_MS = 60_000L

/** Runs a 16 kHz mono WAV through the real engines as fast as possible. Compare OPENSUB_SILENCE values, or read the sentences it produces. */
suspend fun speechEvaluation(path: String) {
    val samples = WavFiles.readMono16k(File(path))
    val silence = System.getenv("OPENSUB_SILENCE")?.toFloatOrNull() ?: 0.55f
    val recognizer = ParakeetRecognizer()
    val detector = SherpaDetector(false, silence, true); val translator = SentenceTranslator(CachingTranslator(desktopTranslator()))
    val output = File(AppPaths.logs, "speech-evaluation.txt").apply { writeText("") }
    fun emit(line: String) { println(line); output.appendText(line + "\n") }
    val pid = ProcessHandle.current().pid()
    fun workingSetMb() = ProcessBuilder("tasklist", "/fi", "PID eq $pid", "/fo", "csv", "/nh").start().inputStream.bufferedReader(java.nio.charset.Charset.defaultCharset()).readText()
        .split("\",\"").last().filter { it.isDigit() }.toLongOrNull()?.div(1024) ?: -1
    try {
        val utterances = ArrayList<Utterance>()
        for (start in samples.indices step 320) utterances += detector.accept(PcmFrame(samples.copyOfRange(start, minOf(start + 320, samples.size)), start.toLong()))
        utterances += detector.flush()
        var asrMs = 0L; var mtMs = 0L; var slowestAsr = 0L; var id = 0L; var windowMs = 0L; var slowestWindow = 0L
        val window = SentenceWindow(recognizer)
        val groups = LinkedHashMap<Long, Pair<SubtitleCue, String>>()
        for (utterance in utterances) {
            var english = ""
            val decode = measureTimeMillis { english = utterance.sentenceText(recognizer.recognize(utterance)) }
            asrMs += decode; slowestAsr = maxOf(slowestAsr, decode)
            if (english.isEmpty()) continue
            emit("%.1f-%.1f | %s".format(utterance.startMs / 1000.0, utterance.endMs / 1000.0, english))
            var sentences = emptyList<SubtitleCue>()
            val regroup = measureTimeMillis { sentences = window.add(SubtitleCue(++id, utterance.startMs, utterance.endMs, english, decode), utterance) }
            windowMs += regroup; slowestWindow = maxOf(slowestWindow, regroup)
            emit("FINAL %.1f-%.1f | %s".format(utterance.startMs / 1000.0, utterance.endMs / 1000.0, window.latestEnglish))
            for (sentence in sentences) {
                var chinese = ""
                mtMs += measureTimeMillis { chinese = translator.translate(sentence.english) }
                groups[sentence.id] = sentence to chinese
            }
        }
        for ((cue, chinese) in groups.values) emit("GROUP %.1f-%.1f # %s # %s".format(cue.startMs / 1000.0, cue.endMs / 1000.0, cue.english, chinese))
        emit("SUMMARY segments=${utterances.size} groups=${groups.size} asrMs=$asrMs slowestAsrMs=$slowestAsr windowMs=$windowMs slowestWindowMs=$slowestWindow mtMs=$mtMs audioSeconds=${samples.size / SAMPLE_RATE} workingSetMb=${workingSetMb()}")
    } finally { detector.close(); recognizer.close(); translator.close() }
}

/** Translates a text file line by line with the real translator, to judge the model apart from recognition and cuts. */
suspend fun translateFile(path: String) {
    val translator = desktopTranslator()
    val output = File(AppPaths.logs, "translate-file.txt").apply { writeText("") }
    try {
        for (line in File(path).readLines().filter { it.isNotBlank() }) {
            var chinese = ""
            val ms = measureTimeMillis { chinese = translator.translate(line) }
            output.appendText("[$ms ms] $line\n    -> $chinese\n")
        }
    } finally { translator.close() }
}

/**
 * The real pipeline for [minutes], fed a looped clip at playback speed. No window and no sound.
 * The cache is bypassed for the short sample on purpose: a looped clip would otherwise stop exercising the translator.
 */
suspend fun soakTest(minutes: Int) {
    AppPaths.requireModels()
    val log = File(AppPaths.logs, "soak-test.txt").apply { writeText("") }
    fun report(line: String) { println(line); log.appendText(line + "\n") }
    val pid = ProcessHandle.current().pid()
    fun workingSetMb() = ProcessBuilder("tasklist", "/fi", "PID eq $pid", "/fo", "csv", "/nh").start().inputStream.bufferedReader(java.nio.charset.Charset.defaultCharset()).readText()
        .split("\",\"").last().filter { it.isDigit() }.toLongOrNull()?.div(1024) ?: -1
    val detector = rollingVad()
    val recognizer = ParakeetRecognizer()
    // Real speech gives far more honest timings than one looped sentence; set OPENSUB_SOAK_WAV to use it.
    val speech = System.getenv("OPENSUB_SOAK_WAV")?.let(::File)
    val translator = CachingTranslator(desktopTranslator())
    val clip = WavFiles.readMono16k(speech ?: File(AppPaths.sampleAudio, "test_wavs/0.wav")) + FloatArray(SAMPLE_RATE)
    val started = System.currentTimeMillis()
    val stats = SoakStats { System.currentTimeMillis() - started }
    var nextReport = 0L
    fun sample() {
        val elapsed = System.currentTimeMillis() - started
        if (elapsed < nextReport) return
        nextReport += MINUTE_MS
        report("SOAK ${elapsed / MINUTE_MS}min ${stats.summary()} workingSetMb=${workingSetMb()}")
    }
    try {
        RollingCaptionPipeline().run(PacedLoopSource(clip, minutes * MINUTE_MS), detector, recognizer, translator = translator,
            onFrame = { sample() }, onTranslation = stats::translation, onCue = { cue ->
                stats.cue(cue)
                if (cue.isFinal) stats.englishPage()
            })
        report("SOAK PASSED minutes=$minutes ${stats.summary()} workingSetMb=${workingSetMb()}")
    } catch (e: Throwable) {
        report("SOAK FAILED ${e.stackTraceToString()}")
        throw e
    } finally { detector.close(); recognizer.close(); translator.close() }
}

/**
 * No window and no audio. Sherpa and ONNX Runtime load side by side here, as in a real session,
 * because they ship native libraries with the same name. Results also go to a file since the packaged exe has no console.
 */
suspend fun translationTest() {
    val log = StringBuilder()
    fun report(line: String) { println(line); log.appendLine(line) }
    try {
        AppPaths.requireModels()
        val recognizer = ParakeetRecognizer()
        val translator: Translator
        val loadMs = measureTimeMillis { translator = desktopTranslator() }
        report("Model load: ${loadMs}ms")
        try {
            recognizer.recognize(Utterance(FloatArray(16_000), 0))
            for (sample in Samples) {
                var chinese = ""
                val ms = measureTimeMillis { chinese = translator.translate(sample) }
                report("[$ms ms] $sample\n    -> $chinese")
                check(chinese.any { it.code in 0x4E00..0x9FFF }) { "Translation contains no Chinese: $chinese" }
            }
            recognizer.recognize(Utterance(FloatArray(16_000), 0))
            report("PASSED")
        } finally { translator.close(); recognizer.close() }
    } catch (e: Throwable) {
        report("FAILED: ${e.stackTraceToString()}")
        throw e
    } finally { File(AppPaths.logs, "translation-test.txt").writeText(log.toString()) }
}
