package com.opensub.app.platform

import com.opensub.app.core.MarianTokenizer
import com.opensub.app.core.Translator
import com.opensub.app.translate.HyTranslator
import com.opensub.app.translate.OnnxMarianTranslator
import java.io.File

/** Four candidates fix garbled long sentences for about 0.1 s more per sentence; eight changed few more and made live Chinese 0.6 s later. */
private const val BEAM_SIZE = 4
/** Physical cores carry the translation; 8, 12 and 16 were as fast (memory speed is the limit), 4 was 0.3 s slower a subtitle. */
private const val HY_THREADS = 8

/** Hy-MT2. Set OPENSUB_MT=firefox or OPENSUB_MT=opus to compare against the smaller models in development. */
fun desktopTranslator(): Translator = when (System.getenv("OPENSUB_MT")) {
    "opus" -> opusTranslator()
    "firefox" -> firefoxTranslator()
    else -> HyTranslator(AppPaths.hyTranslate, AppPaths.hyMt, HY_THREADS, File(AppPaths.logs, "hy-translate.log"))
}

private fun firefoxTranslator(): OnnxMarianTranslator {
    fun model(name: String) = File(AppPaths.firefoxMt, name)
    val tokenizer = MarianTokenizer(model("source.spm").readBytes(), model("vocab.json").readText(),
        model("target_vocab.json").readText(), languageToken = null)
    return OnnxMarianTranslator(tokenizer, threads = 4, beamSize = System.getenv("OPENSUB_BEAM")?.toIntOrNull() ?: BEAM_SIZE) { environment, options ->
        environment.createSession(model("encoder_model.int8.onnx").path, options) to
            environment.createSession(model("decoder_model_merged.int8.onnx").path, options)
    }
}

private fun opusTranslator(): OnnxMarianTranslator {
    val tokenizer = MarianTokenizer(File(AppPaths.opusMt, "source.spm").readBytes(), File(AppPaths.opusMt, "vocab.json").readText())
    return OnnxMarianTranslator(tokenizer, threads = 4) { environment, options ->
        environment.createSession(File(AppPaths.opusMt, "encoder_model_quantized.onnx").path, options) to
            environment.createSession(File(AppPaths.opusMt, "decoder_model_merged_quantized.onnx").path, options)
    }
}
