package com.opensub.app.core

import kotlin.math.abs
import kotlin.test.*

class AutoGainTest {
    private fun tone(level: Float, start: Long = 0) = PcmFrame(FloatArray(320) { if (it % 2 == 0) level else -level }, start)

    private fun AutoGain.settled(level: Float): PcmFrame {
        apply(tone(level))
        return apply(tone(level, 320))
    }

    @Test fun quietSpeechIsBroughtUp() {
        assertEquals(0.5f, AutoGain().settled(0.05f).samples.maxOf { abs(it) }, 0.01f)
    }

    @Test fun loudSpeechIsLeftAlone() {
        val frame = tone(0.8f)
        assertSame(frame, AutoGain().apply(frame))
    }

    @Test fun silenceStaysSilent() {
        assertEquals(0f, AutoGain().settled(0f).samples.maxOf { abs(it) })
    }

    @Test fun roomNoiseIsNotMadeLoud() {
        assertTrue(AutoGain().settled(0.0005f).samples.maxOf { abs(it) } <= 0.0005f * 40 + 0.001f)
    }

    @Test fun aPauseDoesNotRaiseTheNoiseBetweenSentences() {
        val gain = AutoGain()
        gain.settled(0.05f)
        val pause = (0 until 50).map { gain.apply(tone(0.001f, 640L + it * 320)) }.last()
        assertTrue(pause.samples.maxOf { abs(it) } < 0.02f)
    }

    @Test fun timingIsKept() {
        assertEquals(960L, AutoGain().apply(tone(0.05f, 960)).startSample)
    }
}
