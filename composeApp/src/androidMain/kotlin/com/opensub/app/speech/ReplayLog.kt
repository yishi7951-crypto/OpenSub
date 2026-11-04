package com.opensub.app.speech

import android.os.SystemClock
import com.opensub.app.core.*
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * One line of JSON per event, in the format of Windows' recognitionReplay, so the same scripts read a replay and a real run:
 * each reading and how long it took, each translation request, each subtitle and what the overlay shows.
 * Times count from [start].
 */
class ReplayLog(private val file: File) {
    private var clock = SystemClock.elapsedRealtime()
    init { file.writeText("") }

    fun start() { clock = SystemClock.elapsedRealtime() }
    fun now() = SystemClock.elapsedRealtime() - clock
    @Synchronized fun line(kind: String, fields: String = "") = file.appendText("{\"kind\":\"$kind\",\"atMs\":${now()}${if (fields.isEmpty()) "" else ",$fields"}}\n")
    fun record(kind: String, cue: SubtitleCue) = line(kind, "\"id\":${cue.id},\"sentenceId\":${cue.sentenceId},\"startMs\":${cue.startMs},\"endMs\":${cue.endMs}," +
        "\"final\":${cue.isFinal},\"translateMs\":${cue.translateMs},\"english\":${json(cue.english)},\"chinese\":${json(cue.chinese)}")
    fun rows(rows: SentenceCaption.Shown) = record("rows", SubtitleCue(0, 0, 0, rows.english, 0, chinese = rows.chinese, sentenceId = rows.sentence))

    /** [recognizer], with each reading logged. */
    fun timed(recognizer: SpeechRecognizer): SpeechRecognizer = object : SpeechRecognizer by recognizer {
        override suspend fun words(utterance: Utterance): List<HeardWord> {
            val began = now()
            val words = recognizer.words(utterance)
            val offset = utterance.startSample.toFloat() / SAMPLE_RATE
            val end = (utterance.startSample + utterance.samples.size).toFloat() / SAMPLE_RATE
            val heard = words.joinToString(" ") { "${it.text.replace("\"", "")}@${it.startSeconds + offset}" }
            line("read", "\"tookMs\":${now() - began},\"seconds\":${utterance.samples.size / SAMPLE_RATE},\"end\":$end,\"words\":${json(heard)}")
            return words
        }
    }

    /** [translator], with each request and how long it took logged, to tell a slow model from a long queue. */
    fun timed(translator: Translator): Translator = object : Translator by translator {
        private suspend fun asked(how: String, english: String, extra: String, answer: suspend () -> String): String {
            val began = now()
            val chinese = answer()
            line("ask", "\"how\":\"$how\",\"tookMs\":${now() - began},\"english\":${json(english)},\"extra\":${json(extra)},\"chinese\":${json(chinese)}")
            return chinese
        }
        override suspend fun translate(english: String) = asked("plain", english, "") { translator.translate(english) }
        override suspend fun continueFrom(english: String, shown: String) = asked("continue", english, shown) { translator.continueFrom(english, shown) }
        override suspend fun translateAfter(english: String, before: String) = asked("after", english, before) { translator.translateAfter(english, before) }
    }

    private fun json(value: String) = JSONObject.quote(value)
}

/** The captured sound as a 16 kHz mono WAV, for replaying a real run with `--es diagnostic rolling`. */
class WavRecorder(file: File) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var bytes = 0L

    @Synchronized fun write(samples: FloatArray) {
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val value = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
            pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
        }
        out.write(pcm); bytes += pcm.size
    }

    @Synchronized override fun close() {
        fun int(value: Long) = ByteArray(4) { (value shr (it * 8)).toByte() }
        fun short(value: Int) = ByteArray(2) { (value shr (it * 8)).toByte() }
        out.seek(0)
        out.write("RIFF".toByteArray()); out.write(int(36 + bytes)); out.write("WAVEfmt ".toByteArray())
        out.write(int(16)); out.write(short(1)); out.write(short(1)); out.write(int(SAMPLE_RATE.toLong())); out.write(int(SAMPLE_RATE * 2L))
        out.write(short(2)); out.write(short(16)); out.write("data".toByteArray()); out.write(int(bytes))
        out.close()
    }
}
