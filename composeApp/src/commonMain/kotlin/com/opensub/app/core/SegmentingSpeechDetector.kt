package com.opensub.app.core

private const val MAX_PIECE_SAMPLES = 7 * SAMPLE_RATE

/** A cut can only look this far back, because everything after it reaches the screen one piece late. */
private const val MIN_PIECE_SAMPLES = 11 * SAMPLE_RATE / 2

/** Well above the longest piece, so the VAD only ends speech at real pauses. Cuts in long speech come from [findWordGap]. */
const val VAD_MAX_SPEECH_SECONDS = 20f

/** Only the native VAD binding varies by platform; segmentation and preview rules stay shared. */
interface VadEngine {
    fun accept(samples: FloatArray)
    fun speechDetected(): Boolean
    fun drain(): List<Utterance>
    fun flush()
    fun close()
}

class SegmentingSpeechDetector(
    private val engine: VadEngine,
    private val previewsEnabled: Boolean = true,
    private val enforceMaxDuration: Boolean = true,
) : SpeechDetector {
    private val recent = PcmWindow()
    private var speechStart: Long? = null
    private var committedUntil = 0L

    /** What the VAD has finished, minus any part already sent as a cut piece. */
    private fun drain(): List<Utterance> = engine.drain().mapNotNull { segment ->
        val end = segment.startSample + segment.samples.size
        val from = maxOf(segment.startSample, committedUntil)
        committedUntil = maxOf(committedUntil, end)
        speechStart = null
        when {
            from == segment.startSample -> segment
            end <= from -> null
            else -> Utterance(segment.samples.copyOfRange((from - segment.startSample).toInt(), segment.samples.size), from)
        }
    }

    /** The VAD keeps listening: the speech after the cut starts the next piece. */
    private fun cutAtWordGap(start: Long): Utterance {
        val speech = recent.from(start)
        val length = findWordGap(speech.samples, MIN_PIECE_SAMPLES)
        committedUntil = speech.startSample + length
        speechStart = committedUntil
        return Utterance(speech.samples.copyOf(length), speech.startSample, cutMidSpeech = true,
            trailingSamples = speech.samples.copyOfRange(length, speech.samples.size))
    }

    override fun accept(frame: PcmFrame): List<Utterance> {
        recent.append(frame)
        engine.accept(frame.samples)
        val finished = drain().toMutableList()
        if (engine.speechDetected()) {
            if (speechStart == null) speechStart = maxOf(committedUntil, recent.endSample - 7_200, 0)
            if (enforceMaxDuration && recent.endSample - speechStart!! >= MAX_PIECE_SAMPLES) finished += cutAtWordGap(speechStart!!)
        }
        return finished
    }
    override fun preview(): Utterance? {
        if (!previewsEnabled || !engine.speechDetected()) return null
        val start = speechStart ?: return null
        return if (recent.endSample - start >= SAMPLE_RATE) recent.from(start) else null
    }
    override fun flush(): List<Utterance> { engine.flush(); return drain() }
    override fun close() = engine.close()
}
