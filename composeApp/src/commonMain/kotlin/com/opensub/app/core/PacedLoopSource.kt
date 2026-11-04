package com.opensub.app.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

private const val FRAME_SAMPLES = 320

/** Soak-test audio: repeats a clip at real-time speed, so a long run loads the pipeline as playback would. */
class PacedLoopSource(private val clip: FloatArray, private val durationMs: Long) : AudioSource {
    @Volatile private var stopped = false

    override fun frames(): Flow<PcmFrame> = flow {
        val clock = TimeSource.Monotonic.markNow()
        var sample = 0L
        while (!stopped && sample * 1000 / SAMPLE_RATE < durationMs) {
            val due = (sample + FRAME_SAMPLES) * 1000 / SAMPLE_RATE
            val wait = due - clock.elapsedNow().inWholeMilliseconds
            if (wait > 0) delay(wait)
            emit(PcmFrame(FloatArray(FRAME_SAMPLES) { clip[((sample + it) % clip.size).toInt()] }, sample))
            sample += FRAME_SAMPLES
        }
    }

    override fun stop() { stopped = true }
}
