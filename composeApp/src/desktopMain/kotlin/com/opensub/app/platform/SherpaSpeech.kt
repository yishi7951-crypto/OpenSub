package com.opensub.app.platform

import com.k2fsa.sherpa.onnx.*
import com.opensub.app.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SherpaNative {
    @Synchronized fun load() {
        if (loaded) return
        val root = if (System.getProperty("compose.application.resources.dir") != null) File(AppPaths.home, "native") else File(AppPaths.home, ".runtime/sherpa")
        val libraries = root.walkTopDown().filter { it.isFile && it.extension.equals("dll", true) }.toList()
        val nativeDirectory = libraries.singleOrNull { it.name.equals("sherpa-onnx-jni.dll", true) }?.parentFile
            ?: error("缺少 sherpa-onnx-jni.dll，请重新准备 Windows 依赖。")
        fun load(name: String) = System.load(libraries.singleOrNull { it.parentFile == nativeDirectory && it.name.equals(name, true) }?.absolutePath
            ?: error("缺少原生运行库 $name。请重新准备 Windows 依赖。"))
        load("onnxruntime.dll")
        load("sherpa-onnx-jni.dll")
        LibraryLoader.setAutoLoadEnabled(false)
        loaded = true
    }
    private var loaded = false
}

class SherpaDetector(
    previewsEnabled: Boolean = true,
    silenceSeconds: Float = 0.55f,
    enforceMaxDuration: Boolean = true,
) : SpeechDetector by SegmentingSpeechDetector(desktopVad(silenceSeconds), previewsEnabled, enforceMaxDuration)

/** Half a second of quiet counts as a pause. The rolling reader keeps listening across it, so this only decides when to show the last words. */
fun rollingVad(): VadEngine = desktopVad(0.5f)

private fun desktopVad(silenceSeconds: Float): VadEngine {
        AppPaths.requireModels()
        SherpaNative.load()
        val silero = SileroVadModelConfig.builder()
            .setModel(File(AppPaths.models, "silero_vad.onnx").absolutePath)
            .setThreshold(0.5f).setMinSilenceDuration(silenceSeconds).setMinSpeechDuration(0.25f)
            .setMaxSpeechDuration(VAD_MAX_SPEECH_SECONDS).setWindowSize(512).build()
        val vad = Vad(VadModelConfig.builder().setSileroVadModelConfig(silero)
            .setSampleRate(SAMPLE_RATE).setNumThreads(1).setDebug(false).build())
        return object : VadEngine {
            override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)
            override fun speechDetected() = vad.isSpeechDetected
            override fun drain() = buildList {
                while (!vad.empty()) { val segment = vad.front(); add(Utterance(segment.samples, segment.start.toLong())); vad.pop() }
            }
            override fun flush() = vad.flush()
            override fun close() = vad.release()
        }
        }

class ParakeetRecognizer : SpeechRecognizer {
    private val recognizer: OfflineRecognizer
    private val context = RecognitionContext()
    init {
        AppPaths.requireModels()
        SherpaNative.load()
        fun path(name: String) = File(AppPaths.parakeet, name).absolutePath
        val model = OfflineTransducerModelConfig.builder().setEncoder(path("encoder.int8.onnx"))
            .setDecoder(path("decoder.int8.onnx")).setJoiner(path("joiner.int8.onnx")).build()
        val config = OfflineModelConfig.builder().setTransducer(model).setModelType("nemo_transducer").setTokens(path("tokens.txt"))
            .setNumThreads(4).setDebug(false).setProvider("cpu").build()
        recognizer = OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(config).build())
    }
    override suspend fun recognize(utterance: Utterance): String = withContext(Dispatchers.Default) {
        val input = context.input(utterance)
        val result = decode(input.audio)
        val text = if (input.newAudioSeconds == 0f && input.audio.trailingSamples.isEmpty()) result.text
        else input.text(heardWords(result.tokens.asList(), result.timestamps.asList()))
        context.remember(text)
        text
    }
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
