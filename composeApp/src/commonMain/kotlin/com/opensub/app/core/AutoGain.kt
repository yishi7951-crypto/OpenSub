package com.opensub.app.core

import kotlin.math.abs
import kotlin.math.exp

private const val TARGET_PEAK = 0.5f
private const val MOST_GAIN = 40f
/** Long enough to carry across the pauses between sentences, so they do not turn into loud room noise. */
private const val MEMORY_SECONDS = 10f

/**
 * Brings quiet audio up to the level the speech detector and recognizer expect. Captured sound follows the player's own
 * volume, and with a player turned well down the detector misses speech, which then arrives in one burst when it wakes.
 * Only the copy being analysed changes: nothing here touches what the viewer hears. Audio loud enough is left as it is.
 */
class AutoGain {
    private var peak = 0f
    private var gain = 1f

    fun apply(frame: PcmFrame): PcmFrame {
        val samples = frame.samples
        val loudest = samples.maxOfOrNull { abs(it) } ?: return frame
        peak = maxOf(peak * exp(-samples.size / (SAMPLE_RATE * MEMORY_SECONDS)), loudest)
        val wanted = if (peak == 0f) gain else (TARGET_PEAK / peak).coerceIn(1f, MOST_GAIN)
        val step = (wanted - gain) / samples.size
        val first = gain
        gain = wanted
        if (first == 1f && wanted == 1f) return frame
        return PcmFrame(FloatArray(samples.size) { (samples[it] * (first + step * (it + 1))).coerceIn(-1f, 1f) }, frame.startSample)
    }
}
