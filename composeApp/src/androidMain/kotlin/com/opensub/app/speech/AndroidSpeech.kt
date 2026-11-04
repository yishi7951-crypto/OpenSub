package com.opensub.app.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import com.opensub.app.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun androidSpeechDetector(context: Context): SpeechDetector = SegmentingSpeechDetector(androidVad(context, 0.3f))

/** The detector the rolling window uses, as on Windows: half a second of silence is a pause. */
fun androidRollingVad(context: Context): VadEngine = androidVad(context, 0.5f)

private fun androidVad(context: Context, silenceSeconds: Float): VadEngine {
    val vad = Vad(context.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
        model = "models/silero_vad.onnx", minSilenceDuration = silenceSeconds, minSpeechDuration = 0.25f, maxSpeechDuration = VAD_MAX_SPEECH_SECONDS)))
    return object : VadEngine {
        override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)
        override fun speechDetected() = vad.isSpeechDetected()
        override fun drain() = buildList {
            while (!vad.empty()) { val segment = vad.front(); add(Utterance(segment.samples, segment.start.toLong())); vad.pop() }
        }
        override fun flush() = vad.flush()
        override fun close() = vad.release()
    }
}

class AndroidParakeetRecognizer(context: Context, threads: Int = 2) : SpeechRecognizer {
    private val recognizer: OfflineRecognizer
    init {
        val base = "models/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8"
        recognizer = OfflineRecognizer(context.assets, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(encoder = "$base/encoder.int8.onnx", decoder = "$base/decoder.int8.onnx", joiner = "$base/joiner.int8.onnx"),
            modelType = "nemo_transducer", tokens = "$base/tokens.txt", numThreads = threads)))
    }
    override suspend fun recognize(utterance: Utterance): String = withContext(Dispatchers.Default) { decode(utterance).text }
    override suspend fun words(utterance: Utterance): List<HeardWord> = withContext(Dispatchers.Default) {
        val result = decode(utterance)
        heardWords(result.tokens.asList(), result.timestamps.asList())
    }
    private fun decode(utterance: Utterance): OfflineRecognizerResult {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(if (utterance.trailingSamples.isEmpty()) utterance.samples else utterance.samples + utterance.trailingSamples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream)
        } finally { stream.release() }
    }
    override fun close() = recognizer.release()
}
