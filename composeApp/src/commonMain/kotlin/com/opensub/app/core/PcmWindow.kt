package com.opensub.app.core

/** Fixed-size recent audio window. Only previews and cuts in long speech copy samples out of the ring. */
class PcmWindow(private val capacity: Int = 8 * SAMPLE_RATE) {
    init { require(capacity > 0) }
    private val samples = FloatArray(capacity)
    var endSample: Long = 0
        private set
    val startSample: Long get() = (endSample - capacity).coerceAtLeast(0)

    fun append(frame: PcmFrame) {
        require(frame.startSample == endSample) { "PCM frames must have a continuous sample clock" }
        var source = maxOf(0, frame.samples.size - capacity)
        var destination = ((frame.startSample + source) % capacity).toInt()
        while (source < frame.samples.size) {
            val count = minOf(capacity - destination, frame.samples.size - source)
            frame.samples.copyInto(samples, destination, source, source + count)
            source += count
            destination = 0
        }
        endSample = frame.endSample
    }

    fun from(sample: Long): Utterance {
        val start = sample.coerceIn(startSample, endSample)
        val result = FloatArray((endSample - start).toInt())
        val offset = (start % capacity).toInt()
        val first = minOf(result.size, capacity - offset)
        samples.copyInto(result, 0, offset, offset + first)
        if (first < result.size) samples.copyInto(result, first, 0, result.size - first)
        return Utterance(result, start)
    }
}
