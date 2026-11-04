package com.opensub.app.platform

import com.opensub.app.core.SAMPLE_RATE
import com.opensub.app.core.StreamingResampler
import java.io.File
import java.io.RandomAccessFile
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

class WavWriter(val file: File, private val format: MixFormat = MixFormat(SAMPLE_RATE, 1, 16, 1, 2)) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    private var size = 0L
    private var closed = false
    init { output.setLength(0); output.write(ByteArray(44)) }
    fun append(bytes: ByteArray) { check(!closed); output.write(bytes); size += bytes.size }
    fun append(samples: FloatArray) {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, f ->
            val n = (f.coerceIn(-1f, 1f) * 32767).toInt()
            bytes[i * 2] = n.toByte(); bytes[i * 2 + 1] = (n shr 8).toByte()
        }
        append(bytes)
    }
    override fun close() {
        if (closed) return
        closed = true
        output.seek(0)
        fun le32(n: Long) { repeat(4) { output.write((n shr (it * 8)).toInt() and 255) } }
        fun le16(n: Int) { repeat(2) { output.write((n shr (it * 8)) and 255) } }
        output.writeBytes("RIFF"); le32(size + 36); output.writeBytes("WAVEfmt "); le32(16)
        le16(format.encoding); le16(format.channels); le32(format.sampleRate.toLong())
        le32(format.sampleRate.toLong() * format.blockAlign); le16(format.blockAlign); le16(format.bits)
        output.writeBytes("data"); le32(size)
        output.close()
    }
}

object WavFiles {
    fun readMono16k(file: File): FloatArray = AudioSystem.getAudioInputStream(file).use { original ->
        val rate = original.format.sampleRate
        val channels = original.format.channels
        val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false)
        AudioSystem.getAudioInputStream(target, original).use { stream ->
            val bytes = stream.readAllBytes()
            val mono = FloatArray(bytes.size / (channels * 2)) { frame ->
                var sum = 0f
                repeat(channels) { channel ->
                    val offset = (frame * channels + channel) * 2
                    sum += ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort() / 32768f
                }
                sum / channels
            }
            StreamingResampler(rate.toInt()).accept(mono)
        }
    }
    fun play(file: File) {
        AudioSystem.getAudioInputStream(file).use { audio ->
            val line = AudioSystem.getSourceDataLine(audio.format)
            line.open(audio.format)
            try {
                line.start()
                val buffer = ByteArray(8192)
                while (true) { val count = audio.read(buffer); if (count < 0) break; line.write(buffer, 0, count) }
                line.drain()
            } finally { line.stop(); line.close() }
        }
    }
}
