package com.opensub.app.platform

import java.io.File

object AppPaths {
    val home: File = System.getProperty("compose.application.resources.dir")?.let(::File)
        ?: System.getProperty("opensub.home")?.let(::File) ?: File(System.getProperty("user.dir"))
    val models = File(home, "models")
    val parakeet = File(models, "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8")
    /** Development only: the sample speech the Gradle diagnostics use. Not shipped. */
    val sampleAudio = File(models, "sherpa-onnx-moonshine-tiny-en-int8")
    val hyMt = File(models, "hy-mt2-1.8b-1.25bit/Hy-MT2-1.8B-1.25Bit.gguf")
    val speakerModel = File(models, "speaker/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx")
    val hyTranslate: File = File(home, "native/hy-translate.exe").takeIf { it.isFile } ?: File(home, ".runtime/hy-mt/hy-translate.exe")
    val audioCapture: File = File(home, "native/audio-capture.exe").takeIf { it.isFile } ?: File(home, ".runtime/audio-capture/audio-capture.exe")
    /** Development only, for comparison. Not shipped. */
    val firefoxMt = File(models, "firefox-en-zh-int8")
    /** Development only on Windows, for comparison: the Android build still ships this model. */
    val opusMt = File(models, "opus-mt-en-zh-int8")
    val data: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "OpenSub").apply { mkdirs() }
    val recordings: File = File(data, "recordings").apply { mkdirs() }
    val logs: File = File(data, "logs").apply { mkdirs() }
    val settings = File(data, "settings.properties")

    fun requireModels() {
        val required = listOf(File(models, "silero_vad.onnx"), hyMt, hyTranslate) +
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt").map { File(parakeet, it) }
        val missing = required.filterNot { it.isFile }
        check(missing.isEmpty()) { "缺少离线模型：${missing.joinToString { it.name }}。开发环境请先运行 scripts/Setup-Windows.ps1。" }
    }
}
