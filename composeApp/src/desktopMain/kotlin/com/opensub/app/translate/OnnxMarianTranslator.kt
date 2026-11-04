package com.opensub.app.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.opensub.app.core.MarianTokenizer
import com.opensub.app.core.Translator
import com.opensub.app.core.repeatedTail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln

private const val MAX_NEW_TOKENS = 96
private const val REMEMBERED_OUTPUTS = 16

/** [score] is the mean log probability per token: closer to zero means the model is surer of [chinese]. */
data class ScoredTranslation(val chinese: String, val score: Float)

/** Same source on desktop and Android: both expose ONNX Runtime's Java API. */
class OnnxMarianTranslator(
    private val tokenizer: MarianTokenizer,
    threads: Int,
    private val beamSize: Int = 1,
    openSession: (OrtEnvironment, OrtSession.SessionOptions) -> Pair<OrtSession, OrtSession>,
) : Translator {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        // The exported graph has unused constants; each one would otherwise log a harmless warning.
        setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
    }
    private val sessions = openSession(environment, options)
    private val encoder = sessions.first
    private val decoder = sessions.second
    private val pastNames = decoder.inputNames.filter { it.startsWith("past_key_values.") }
    private val recent = ArrayDeque<List<Long>>()

    private class Hypothesis(val tokens: List<Long>, val score: Float, val state: OrtSession.Result? = null) {
        val meanScore: Float get() = score / (tokens.size + 1)
    }

    override suspend fun translate(english: String): String = translateScored(english).chinese

    override suspend fun continueFrom(english: String, shown: String): String = translateScored(english, tokensOf(shown)).chinese

    suspend fun translateScored(english: String, forced: List<Long> = emptyList()): ScoredTranslation = withContext(Dispatchers.Default) {
        val ids = tokenizer.encode(english)
        val mask = LongArray(ids.size) { 1 }
        OnnxTensor.createTensor(environment, arrayOf(ids)).use { input ->
            OnnxTensor.createTensor(environment, arrayOf(mask)).use { attention ->
                encoder.run(mapOf("input_ids" to input, "attention_mask" to attention)).use { encoded ->
                    val best = search(encoded.get(0) as OnnxTensor, attention, ids.size, forced)
                    recent.addLast(best.tokens)
                    if (recent.size > REMEMBERED_OUTPUTS) recent.removeFirst()
                    ScoredTranslation(tokenizer.decode(best.tokens), best.meanScore)
                }
            }
        }
    }

    /** The model's own tokens for [shown], from a recent output. Chinese has no tokenizer here, and only these are sure to continue well. */
    private fun tokensOf(shown: String): List<Long> {
        if (shown.isEmpty()) return emptyList()
        for (tokens in recent.asReversed()) {
            for (count in tokens.size downTo 1) if (tokenizer.decode(tokens.take(count)) == shown) return tokens.take(count)
        }
        return emptyList()
    }

    /**
     * Keeps the [beamSize] likeliest partial translations at each step; a beam of one is plain greedy decoding.
     * Each candidate reuses the decoder state of the step before it. Cross-attention state only exists on step one.
     * The first steps take the [forced] tokens instead of choosing, so the search carries on from text already shown.
     */
    private fun search(encoded: OnnxTensor, attention: OnnxTensor, sourceSize: Int, forced: List<Long>): Hypothesis {
        val empty = pastNames.associateWith { name -> OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), emptyState(name)) }
        val open = LinkedHashSet<OrtSession.Result>()
        val finished = ArrayList<Hypothesis>()
        var live = listOf(Hypothesis(emptyList(), 0f))
        var first: OrtSession.Result? = null
        try {
            for (step in 0 until minOf(MAX_NEW_TOKENS, sourceSize * 4 + 16)) {
                val expanded = ArrayList<Hypothesis>()
                for (beam in live) {
                    val past = pastNames.associateWith { name ->
                        if (step == 0) empty.getValue(name) else {
                            val owner = if (".encoder." in name) first!! else beam.state!!
                            owner.get(name.replace("past_key_values.", "present.")).get() as OnnxTensor
                        }
                    }
                    val token = beam.tokens.lastOrNull() ?: tokenizer.padding
                    val result = OnnxTensor.createTensor(environment, arrayOf(longArrayOf(token))).use { current ->
                        OnnxTensor.createTensor(environment, booleanArrayOf(step > 0)).use { useCache ->
                            decoder.run(past + mapOf("input_ids" to current, "encoder_attention_mask" to attention,
                                "encoder_hidden_states" to encoded, "use_cache_branch" to useCache))
                        }
                    }
                    open += result
                    if (first == null) first = result
                    val choices = if (step < forced.size) listOf(forced[step] to 0f) else likeliest(result.get("logits").get() as OnnxTensor)
                    for ((next, probability) in choices) {
                        expanded += Hypothesis(beam.tokens + next, beam.score + probability, result)
                    }
                }
                val next = ArrayList<Hypothesis>()
                for (candidate in expanded.sortedByDescending { it.score }.take(beamSize)) {
                    val loop = if (step < forced.size) 0 else repeatedTail(candidate.tokens)
                    when {
                        candidate.tokens.last() == tokenizer.endOfSentence -> finished += Hypothesis(candidate.tokens.dropLast(1), candidate.score)
                        loop > 0 -> finished += Hypothesis(candidate.tokens.dropLast(loop), candidate.score)
                        else -> next += candidate
                    }
                }
                val kept = next.mapNotNull { it.state }.toSet()
                open.filter { it !in kept && it !== first }.forEach { it.close(); open -= it }
                live = next
                if (live.isEmpty() || finished.size >= beamSize) break
            }
        } finally {
            open.forEach { it.close() }
            empty.values.forEach { it.close() }
        }
        return finished.ifEmpty { live }.maxBy { it.meanScore }
    }

    /** The decoder's state before its first step: batch 1, no steps taken, other sizes as the model declares them. */
    private fun emptyState(name: String): LongArray {
        val shape = (decoder.inputInfo.getValue(name).info as TensorInfo).shape.copyOf()
        shape[0] = 1
        shape[2] = 0
        return shape
    }

    /** The [beamSize] likeliest next tokens with their log probabilities. Padding is never output. */
    private fun likeliest(logits: OnnxTensor): List<Pair<Long, Float>> {
        val size = logits.info.shape.last().toInt()
        val data = logits.floatBuffer
        val offset = data.remaining() - size
        var highest = Float.NEGATIVE_INFINITY
        for (index in 0 until size) if (index.toLong() != tokenizer.padding) highest = maxOf(highest, data.get(offset + index))
        var total = 0.0
        val best = ArrayList<Int>(beamSize + 1)
        for (index in 0 until size) {
            if (index.toLong() == tokenizer.padding) continue
            val value = data.get(offset + index)
            total += exp((value - highest).toDouble())
            if (best.size < beamSize || value > data.get(offset + best.last())) {
                val position = best.indexOfFirst { value > data.get(offset + it) }.let { if (it < 0) best.size else it }
                best.add(position, index)
                if (best.size > beamSize) best.removeAt(beamSize)
            }
        }
        val normal = ln(total).toFloat()
        return best.map { it.toLong() to data.get(offset + it) - highest - normal }
    }

    override fun close() { decoder.close(); encoder.close(); options.close() }
}
