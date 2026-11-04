package com.opensub.app.core

import kotlin.test.*

class PlaybackWatchTest {
    private fun frame(level: Float) = PcmFrame(FloatArray(320) { level }, 0)

    private fun PlaybackWatch.feed(level: Float, frames: Int) = (1..frames).mapNotNull { accept(frame(level)) }

    @Test fun quietSoundIsNotABreak() {
        assertTrue(PlaybackWatch().feed(0.0001f, 100).isEmpty())
    }

    @Test fun aGlitchIsNotABreak() {
        val watch = PlaybackWatch()
        assertTrue(watch.feed(0.1f, 5).isEmpty())
        assertTrue(watch.feed(0f, 1).isEmpty())
        assertTrue(watch.feed(0.1f, 5).isEmpty())
    }

    @Test fun theSixtyMillisecondsOfARealSeekIsABreak() {
        val watch = PlaybackWatch()
        watch.feed(0.1f, 5)
        assertEquals(listOf(PlaybackWatch.Change.Stopped), watch.feed(0f, 3))
        assertEquals(PlaybackWatch.Change.Resumed, watch.accept(frame(0.1f)))
        assertEquals(60, watch.endedGapMs)
        assertEquals(0.1f, watch.gapLevelBefore)
    }

    @Test fun aGapInsideOneFrameStopsThenResumes() {
        val watch = PlaybackWatch()
        val samples = FloatArray(1600) { if (it in 200 until 1000) 0f else 0.1f }
        assertEquals(PlaybackWatch.Change.Stopped, watch.accept(PcmFrame(samples, 0)))
        assertEquals(PlaybackWatch.Change.Resumed, watch.accept(frame(0.1f)))
    }

    @Test fun aLongGapIsOneBreak() {
        val watch = PlaybackWatch()
        watch.feed(0.1f, 5)
        assertEquals(listOf(PlaybackWatch.Change.Stopped), watch.feed(0f, 50))
    }

    @Test fun resumingIsNotAStop() {
        val watch = PlaybackWatch()
        watch.feed(0f, 10)
        watch.feed(0.1f, 3)
        assertTrue(watch.feed(0f, 1).isEmpty())
    }
}
