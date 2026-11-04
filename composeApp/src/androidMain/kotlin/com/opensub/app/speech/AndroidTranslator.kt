package com.opensub.app.speech

import android.content.Context
import com.opensub.app.core.Translator
import com.opensub.app.translate.HyTranslator
import java.io.File

/** The same weights as Windows' model, as TQ2_0 (`scripts/stq_to_tq2.py`): a reply took 20% less time on the tablet, word for word the same. */
private const val MODEL = "models/hy-mt2-1.8b-1.25bit-tq2/Hy-MT2-1.8B-1.25Bit-TQ2.gguf"
/** Copied out by an earlier version, 462 MB of space for nothing. */
private const val OLD_MODEL = "models/hy-mt2-1.8b-1.25bit"
/**
 * The tablet's four big cores: on its own a whole sentence took 0.74 s with four threads, 0.92 s with three and 1.25 s with
 * two. The recognizer's two threads run beside them. With two busy programs beside a replay, as a playing video would be,
 * three were no better than four: the runs differed more among themselves (Chinese 2.6 to 3.0 s) than between the two.
 */
private const val THREADS = 4

/**
 * Hy-MT2 through hy-translate, as on Windows. The first start copies the model out of the APK, which takes a few seconds.
 */
fun androidTranslator(context: Context, threads: Int = THREADS): Translator =
    OnItsOwn(HyTranslator(File(context.applicationInfo.nativeLibraryDir, program()), modelFile(context), threads,
        File(context.filesDir, "hy-translate.log")))

/**
 * The fast build needs the dot-product and half-precision instructions (ARMv8.2, in most phones and tablets since 2019);
 * arm64-v8a alone does not promise them, and without them the fast build dies on its first instruction. Older chips get the
 * build for the base instruction set: the same translations, slower.
 */
internal fun program(): String {
    val features = runCatching { File("/proc/cpuinfo").readLines() }.getOrDefault(emptyList())
        .filter { it.startsWith("Features") }.flatMap { it.substringAfter(':').trim().split(' ') }.toSet()
    return if ("asimddp" in features && "asimdhp" in features) "libhytranslate.so" else "libhytranslate_base.so"
}

/**
 * Each subtitle asked alone, with no background and not as the rest of the one before. The tablet reads a prompt barely
 * faster than it writes a reply, about 25 ms a token. A request with the Chinese said before as background shares no start
 * with the plain requests around it, so all of it is read again: in a replay of the user's recording that was 18 of the
 * translator's 25 seconds, 8 without. Carrying on hands the model the sentence so far in both languages, 20 to 35 words on
 * a fast talk the user watched on YouTube, and requests took 2 s each: the Chinese came 2.8 s after the words (one in ten
 * 4.9 s) and four subtitles never showed. Alone: 2.6 s (3.3 s), one. Short pieces read stiffer (keep the code base → 保留代码库).
 */
private class OnItsOwn(private val translator: Translator) : Translator by translator {
    override suspend fun translateAfter(english: String, before: String) = translator.translate(english)
    override val continues get() = false
    override val readsBackground get() = false
}

/** The model as a file, since llama.cpp reads it from one: copied from the APK once, and again when an update brings another. */
private fun modelFile(context: Context): File {
    File(context.filesDir, OLD_MODEL).deleteRecursively()
    val target = File(context.filesDir, MODEL)
    val size = context.assets.openFd(MODEL).use { it.length }
    if (target.length() == size) return target
    target.parentFile?.mkdirs()
    val partial = File(target.path + ".part")
    context.assets.open(MODEL).use { input -> partial.outputStream().use { input.copyTo(it, 1 shl 20) } }
    check(partial.renameTo(target)) { "翻译模型复制失败，请检查平板的剩余空间。" }
    return target
}
