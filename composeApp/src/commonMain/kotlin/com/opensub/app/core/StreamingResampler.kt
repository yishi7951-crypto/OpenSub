package com.opensub.app.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Streaming low-pass FIR + fractional resampling; history survives arbitrary packet boundaries. */
class StreamingResampler(private val inputRate: Int, private val outputRate: Int = SAMPLE_RATE) {
    init { require(inputRate > 0 && outputRate > 0) }
    private val taps = 63
    private val half = (taps - 1) / 2.0
    private val history = FloatArray(taps)
    private var cursor = 0
    private var inputCount = 0L
    private var outputCount = 0L
    private val coefficients = Array(128) { phase ->
        val fraction = phase / 128.0
        val cutoff = 0.46 * min(1.0, outputRate.toDouble() / inputRate)
        val weights = DoubleArray(taps) { j ->
            val distance = half - j + fraction
            val x = 2 * cutoff * distance
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            2 * cutoff * sinc * (0.5 + 0.5 * cos(PI * distance / (half + 1)))
        }
        val sum = weights.sum()
        FloatArray(taps) { (weights[it] / sum).toFloat() }
    }

    fun accept(samples: FloatArray): FloatArray {
        if (inputRate == outputRate) return samples.copyOf()
        val output = FloatArray(ceil((samples.size + 1.0) * outputRate / inputRate).toInt() + 2)
        var written = 0
        for (sample in samples) {
            history[cursor] = sample
            val position = inputCount++
            var nextAt = outputCount.toDouble() * inputRate / outputRate
            while (nextAt <= position) {
                val phase = floor((position - nextAt) * 128).toInt().coerceIn(0, 127)
                val weights = coefficients[phase]
                var result = 0f
                var index = cursor
                for (j in 0 until taps) {
                    result += history[index] * weights[j]
                    index = if (index == 0) taps - 1 else index - 1
                }
                output[written++] = result.coerceIn(-1f, 1f)
                outputCount++
                nextAt = outputCount.toDouble() * inputRate / outputRate
            }
            cursor = (cursor + 1) % taps
        }
        return output.copyOf(written)
    }
}
