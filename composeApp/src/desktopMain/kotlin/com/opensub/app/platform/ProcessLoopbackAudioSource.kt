package com.opensub.app.platform

import com.opensub.app.core.AudioSource
import com.opensub.app.core.PcmFrame
import com.opensub.app.core.StreamingResampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** The usual mix rate: OpenSub's own resampler then makes the 16 kHz the recognizer was tested with. */
private const val CAPTURE_RATE = 48_000
private const val FRAME_SAMPLES = CAPTURE_RATE / 50

/**
 * What every other program plays, browser or app, taken before the system volume by the audio-capture program, so muted
 * speakers still give subtitles. On the user's computer the playback device's loopback ([WasapiAudioSource]) heard only
 * zeros once the system was muted, while this heard the browser at its full level. Where the program cannot start
 * (Windows before 10 2004, or a missing file), [fallback] takes over and the muted-speaker note comes back.
 */
class ProcessLoopbackAudioSource(private val program: File, private val fallback: AudioSource, private val onFallback: () -> Unit) : AudioSource {
    private val running = AtomicBoolean(true)
    @Volatile private var process: Process? = null

    override fun stop() {
        running.set(false)
        process?.destroy()
        fallback.stop()
    }

    override fun frames(): Flow<PcmFrame> = flow {
        val errors = File(AppPaths.logs, "audio-capture.log")
        val started = runCatching {
            ProcessBuilder(program.path, ProcessHandle.current().pid().toString(), CAPTURE_RATE.toString())
                .redirectError(errors).start()
        }.getOrNull()
        process = started
        if (!running.get()) started?.destroy()
        val input = started?.let { DataInputStream(it.inputStream.buffered(FRAME_SAMPLES * 16)) }
        val bytes = ByteArray(FRAME_SAMPLES * 4)
        val converter = StreamingResampler(CAPTURE_RATE)
        var outputSamples = 0L
        var heard = false
        while (input != null && running.get()) {
            try { input.readFully(bytes) } catch (_: EOFException) { break }
            heard = true
            val mono = FloatArray(FRAME_SAMPLES)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(mono)
            val samples = converter.accept(mono)
            if (samples.isNotEmpty()) {
                emit(PcmFrame(samples, outputSamples))
                outputSamples += samples.size
            }
        }
        started?.destroy()
        if (!running.get()) return@flow
        if (!heard) {
            onFallback()
            emitAll(fallback.frames())
        } else error("内录程序意外退出，请重新开始。如果反复出现，请贴出 ${errors.path} 的内容。")
    }.flowOn(Dispatchers.IO)
}
