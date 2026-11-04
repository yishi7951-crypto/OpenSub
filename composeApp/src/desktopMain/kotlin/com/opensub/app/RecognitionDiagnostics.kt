package com.opensub.app

import com.opensub.app.core.*
import com.opensub.app.platform.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import java.io.File
import kotlin.time.TimeSource

/** Remembers each reading on disk, so scoring a new setting costs seconds: only readings never made before reach the model. */
private class RememberingRecognizer(private val file: File) : SpeechRecognizer {
    private val real by lazy { ParakeetRecognizer() }
    private val known = HashMap<String, List<HeardWord>>()
    var asked = 0
        private set

    init {
        if (file.exists()) file.forEachLine { line ->
            val parts = line.split('\t')
            known[parts[0]] = parts[1].split(' ').filter { it.isNotEmpty() }.map { HeardWord(it.substringBeforeLast('|'), it.substringAfterLast('|').toFloat()) }
        }
    }

    override suspend fun recognize(utterance: Utterance) = real.recognize(utterance)
    override suspend fun words(utterance: Utterance) = known.getOrPut("${utterance.startSample}:${utterance.samples.size}") {
        asked++
        real.words(utterance).also { heard ->
            file.appendText("${utterance.startSample}:${utterance.samples.size}\t${heard.joinToString(" ") { "${it.text}|${it.startSeconds}" }}\n")
        }
    }
    override fun close() { if (asked > 0) real.close() }
}

/**
 * Scores recognition settings on a recording without the real-time clock. Each trial is "settleSeconds/longestWindowSeconds/leftContextSeconds/stepSeconds".
 * Writes the sentences to logs/score-<recording>-<trial>.txt; scripts/score_transcript.py compares them with the reference.
 */
suspend fun transcriptScore(path: String, seconds: Int, trials: List<String>) {
    val recording = File(path)
    val samples = WavFiles.readMono16k(recording).take(seconds * SAMPLE_RATE).toFloatArray() + FloatArray(SAMPLE_RATE)
    val cache = File(AppPaths.logs, "read-cache").apply { mkdirs() }.resolve("${recording.nameWithoutExtension}.tsv")
    val recognizer = RememberingRecognizer(cache)
    val window = 45 * SAMPLE_RATE
    for (trial in trials) {
        val (settle, longest, left, stepSeconds) = trial.split('/').map { it.toFloat() }
        val step = (SAMPLE_RATE * stepSeconds).toInt()
        val reader = RollingReader(recognizer, RollingTranscript(settle, longest, left))
        val detector = rollingVad()
        val sentences = ArrayList<String>()
        val waits = ArrayList<Float>()
        val timed = ArrayList<String>()
        var speaking = false
        var lastRead = 0
        fun keep(result: RollingReader.Step?, now: Float) {
            result ?: return
            result.update.revisedLast?.let { text -> timed[timed.lastIndex] = "$text\t${timed.last().substringAfter('\t')}" }
            result.update.words.forEach { waits += now - it.startSeconds; timed += "${it.text}\t${it.startSeconds}" }
            result.cues.filter { it.isFinal }.forEach { sentences += it.english }
        }
        val mark = TimeSource.Monotonic.markNow()
        val asked = recognizer.asked
        for (start in samples.indices step 320) {
            val end = minOf(start + 320, samples.size)
            detector.accept(samples.copyOfRange(start, end))
            detector.drain()
            if (end - lastRead < step && end < samples.size) continue
            lastRead = end
            val nowSpeaking = detector.speechDetected() && end < samples.size
            val from = maxOf((reader.keepFromSeconds * SAMPLE_RATE).toInt(), end - window)
            val clip = if (nowSpeaking || speaking) Utterance(samples.copyOfRange(from, end), from.toLong()) else null
            speaking = nowSpeaking
            keep(reader.step(clip, nowSpeaking, end.toLong()), end.toFloat() / SAMPLE_RATE)
        }
        keep(reader.finish(), samples.size.toFloat() / SAMPLE_RATE)
        detector.close()
        File(AppPaths.logs, "score-${recording.nameWithoutExtension}-${trial.replace('/', '_')}.txt").writeText(sentences.joinToString("\n") + "\n")
        File(AppPaths.logs, "words-${recording.nameWithoutExtension}-${trial.replace('/', '_')}.tsv").writeText(timed.joinToString("\n") + "\n")
        val ordered = waits.sorted()
        println("SCORE $trial sentences=${sentences.size} words=${ordered.size} waitP50=${ordered[ordered.size / 2]} waitP90=${ordered[ordered.size * 9 / 10]} newReadings=${recognizer.asked - asked} ms=${mark.elapsedNow().inWholeMilliseconds}")
    }
    recognizer.close()
}

/**
 * Voice fingerprints of a recording, for telling speakers apart offline: one for every 1.5 s stretch, 0.25 s apart, in
 * `speaker-chunks.tsv`, and one for each `start<TAB>end` line (seconds) of [segments] in `speaker-segments.tsv`.
 */
fun speakerProbe(path: String, segments: String) {
    val clip = WavFiles.readMono16k(File(path))
    SherpaVoiceprint().use { voice ->
        val mark = TimeSource.Monotonic.markNow()
        val chunks = StringBuilder()
        var start = 0
        while (start + SAMPLE_RATE * 3 / 2 <= clip.size) {
            voice.of(clip.copyOfRange(start, start + SAMPLE_RATE * 3 / 2))?.let { chunks.append(start / SAMPLE_RATE.toFloat()).append('\t').append(it.joinToString("\t")).append('\n') }
            start += SAMPLE_RATE / 4
        }
        File(AppPaths.logs, "speaker-chunks.tsv").writeText(chunks.toString())
        println("SPEAKER chunks=${chunks.count { it == '\n' }} ms=${mark.elapsedNow().inWholeMilliseconds}")
        if (segments.isNotEmpty()) {
            val out = StringBuilder()
            for (line in File(segments).readLines().filter { it.isNotBlank() }) {
                val (from, to) = line.split('\t').map { it.toFloat() }
                val samples = clip.copyOfRange((from * SAMPLE_RATE).toInt().coerceIn(0, clip.size), (to * SAMPLE_RATE).toInt().coerceIn(0, clip.size))
                val print = voice.of(samples)
                out.append(from).append('\t').append(to).append('\t').append(print?.joinToString("\t") ?: "").append('\n')
            }
            File(AppPaths.logs, "speaker-segments.tsv").writeText(out.toString())
            println("SPEAKER segments written")
        }
    }
}

/** Replays local speech at its real speed, without speakers or windows. Logs what the reader actually receives. */
suspend fun recognitionReplay(path: String, seconds: Int) {
    require(seconds in 1..180)
    val clip = WavFiles.readMono16k(File(path)).take(seconds * SAMPLE_RATE).toFloatArray() + FloatArray(SAMPLE_RATE)
    val log = File(AppPaths.logs, "recognition-replay.jsonl").apply { writeText("") }
    val clock = TimeSource.Monotonic.markNow()
    val parakeet = ParakeetRecognizer()
    // Every reading with its timing, to see how long a word takes from the voice to the screen.
    val recognizer = object : SpeechRecognizer by parakeet {
        override suspend fun words(utterance: Utterance): List<HeardWord> {
            val began = clock.elapsedNow().inWholeMilliseconds
            val words = parakeet.words(utterance)
            val offset = utterance.startSample.toFloat() / SAMPLE_RATE
            val heard = words.joinToString(" ") { "${it.text.replace("\"", "")}@${it.startSeconds + offset}" }
            val end = (utterance.startSample + utterance.samples.size).toFloat() / SAMPLE_RATE
            log.appendText("{\"kind\":\"read\",\"atMs\":${clock.elapsedNow().inWholeMilliseconds},\"tookMs\":${clock.elapsedNow().inWholeMilliseconds - began},\"seconds\":${utterance.samples.size / SAMPLE_RATE},\"end\":$end,\"words\":\"${heard.replace("\\", "")}\"}\n")
            return words
        }
    }
    // When the speech detector turns on and off, in audio time.
    val vad = rollingVad()
    val detector = object : VadEngine by vad {
        var heardSamples = 0L
        var was = false
        override fun accept(samples: FloatArray) {
            vad.accept(samples)
            heardSamples += samples.size
            val now = vad.speechDetected()
            if (now != was) log.appendText("{\"kind\":\"vad\",\"atMs\":${clock.elapsedNow().inWholeMilliseconds},\"audioS\":${heardSamples.toFloat() / SAMPLE_RATE},\"speaking\":$now}\n")
            was = now
        }
    }
    val translator = CachingTranslator(desktopTranslator())
    val style = SubtitleStyle(fontSize = 31f, englishFontSize = 31f)
    var history = emptyList<SubtitleCue>()
    var pages = 0
    var lastPage = -1L
    var previews = 0
    var firstDisplayMs: Long? = null
    val finalLags = ArrayList<Long>()
    var nextReport = 15_000L
    fun record(kind: String, cue: SubtitleCue) {
        fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        log.appendText("{\"kind\":\"$kind\",\"atMs\":${clock.elapsedNow().inWholeMilliseconds},\"id\":${cue.id},\"sentenceId\":${cue.sentenceId},\"startMs\":${cue.startMs},\"endMs\":${cue.endMs},\"final\":${cue.isFinal},\"translateMs\":${cue.translateMs},\"english\":${json(cue.english)},\"chinese\":${json(cue.chinese)},\"line\":${json(cue.chineseLine)},\"sentenceEnglish\":${json(cue.sentenceEnglish)},\"sentenceChinese\":${json(cue.sentenceChinese)}}\n")
    }
    // The window's next sentence waits on a clock, so the replay looks again every frame.
    var tick: () -> Unit = {}
    val source = object : AudioSource {
        override fun frames() = flow {
            for (start in clip.indices step 320) {
                val end = minOf(start + 320, clip.size)
                val wait = end * 1000L / SAMPLE_RATE - clock.elapsedNow().inWholeMilliseconds
                if (wait > 0) delay(wait)
                emit(PcmFrame(clip.copyOfRange(start, end), start.toLong()))
                tick()
                if (end * 1000L / SAMPLE_RATE >= nextReport) {
                    println("REPLAY ${nextReport / 1000}s previews=$previews pages=$pages")
                    nextReport += 15_000
                }
            }
        }
        override fun stop() {}
    }
    try {
        // The rows as the subtitle window shows them: one sentence at a time, with its own Chinese.
        val caption = SentenceCaption({ englishRowChars(style) }, { chineseRowChars(style) })
        var shown = SentenceCaption.Shown()
        fun showRows(change: (nowMs: Long) -> SentenceCaption.Shown) = synchronized(caption) {
            val now = change(clock.elapsedNow().inWholeMilliseconds)
            if (now != shown) {
                shown = now
                record("rows", SubtitleCue(0, 0, 0, now.english, 0, chinese = now.chinese, sentenceId = now.sentence))
            }
        }
        tick = { showRows { caption.advance(it) } }
        RollingCaptionPipeline().run(source, detector, recognizer, translator = translator,
            onResumed = {
                record("resumed", SubtitleCue(0, 0, 0, "", 0))
                synchronized(caption) { caption.clear() }
            },
            onUnit = {
                record("unit", it)
                showRows { now -> caption.chinese(it.sentenceId, it.id, subtitlePunctuation(it.chinese), now) }
            },
            onSentenceUnits = { sentence -> showRows { caption.finished(sentence, it) } },
            onLine = { cue ->
                record("display", cue)
                if (!cue.cut) showRows { caption.english(cue.sentenceId, cue.english, it) }
                if (firstDisplayMs == null) firstDisplayMs = clock.elapsedNow().inWholeMilliseconds
                if (cue.id != lastPage) { lastPage = cue.id; pages++ }
            },
            onTranslation = { cue ->
                record("translation", cue)
                if (cue.isFinal) {
                    history = foldTranslation(history, cue)
                    finalLags += (clock.elapsedNow().inWholeMilliseconds - cue.endMs).coerceAtLeast(0)
                }
            },
            onCue = { cue ->
                record("recognition", cue)
                if (cue.isFinal) history = foldTranslation(history, cue) else previews++
            })
        history.forEach { record("history", it) }
        val lags = finalLags.sorted()
        println("REPLAY PASSED seconds=$seconds firstDisplayMs=$firstDisplayMs previews=$previews pages=$pages chineseLagMs(p50=${lags.getOrNull(lags.size / 2)} max=${lags.lastOrNull()}) report=${log.absolutePath}")
    } finally { detector.close(); recognizer.close(); translator.close() }
}
