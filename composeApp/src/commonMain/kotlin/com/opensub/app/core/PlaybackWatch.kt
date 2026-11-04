package com.opensub.app.core

import kotlin.math.abs

/** Below anything a recording holds: even a quiet room has more noise than this after a recording's own processing. */
private const val SILENT_SAMPLE = 1e-6f
/** A seek in PotPlayer left only 60 ms, and two minutes of real speech left none at all. */
private const val BREAK_MS = 30L
/** Gaps worth noting down, to learn what real players do. */
private const val NOTED_GAP_MS = 10L

/**
 * Notices playback stopping, from the sound alone, so it works for any player on any platform. A video that is sought,
 * paused or buffering stops feeding the mixer, and captured sound drops to exact zero, which recordings do not do. Speech
 * after that may come from anywhere in the video, so it must not be read as carrying on from what came before.
 * Samples are counted one by one, because a short gap can fall inside a single frame.
 */
class PlaybackWatch {
    enum class Change { Stopped, Resumed }

    private var silentSamples = 0L
    private var stopped = false
    private var resumeNext = false
    private var lastLevel = 0f

    /** Length of the gap that just ended, for logging. Zero when no gap ended. */
    var endedGapMs = 0L
        private set
    var gapLevelBefore = 0f
        private set

    fun accept(frame: PcmFrame): Change? {
        endedGapMs = 0
        var change = if (resumeNext) Change.Resumed else null
        resumeNext = false
        for (sample in frame.samples) {
            val level = abs(sample)
            if (level < SILENT_SAMPLE) {
                silentSamples++
                if (!stopped && silentSamples * 1000 / SAMPLE_RATE >= BREAK_MS) {
                    stopped = true
                    change = Change.Stopped
                }
                continue
            }
            val gapMs = silentSamples * 1000 / SAMPLE_RATE
            if (gapMs >= NOTED_GAP_MS) {
                endedGapMs = gapMs
                gapLevelBefore = lastLevel
            }
            silentSamples = 0
            lastLevel = level
            if (stopped) {
                stopped = false
                // Both in one frame: the stop has to be handled first.
                if (change == Change.Stopped) resumeNext = true else change = Change.Resumed
            }
        }
        return change
    }
}
