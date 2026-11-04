package com.opensub.app.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.opensub.app.core.AudioSource
import com.opensub.app.core.PcmFrame
import com.opensub.app.core.SAMPLE_RATE
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Captures only media/game playback. No microphone AudioSource is ever selected. */
class AndroidPlaybackAudioSource(private val projection: MediaProjection, private val seconds: Int? = 10) : AudioSource {
    private val running = AtomicBoolean(true)
    private val lock = Any()
    private var recorder: AudioRecord? = null

    override fun stop() {
        running.set(false)
        synchronized(lock) {
            recorder?.takeIf { it.recordingState == AudioRecord.RECORDSTATE_RECORDING }?.stop()
        }
    }

    @Suppress("MissingPermission")
    override fun frames(): Flow<PcmFrame> = callbackFlow {
        val worker = thread(name = "OpenSub-PlaybackCapture", isDaemon = true) {
            var local: AudioRecord? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "当前设备无法创建 16kHz 单声道录音缓冲区：$minimum" }
                val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                local = AudioRecord.Builder()
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, SAMPLE_RATE / 5 * 2))
                    .setAudioPlaybackCaptureConfig(capture).build()
                val audio = local
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "系统播放捕获初始化失败。" }
                synchronized(lock) {
                    recorder = audio
                    if (running.get()) audio.startRecording()
                }
                val block = ShortArray(320)
                var position = 0L
                val limit = seconds?.toLong()?.times(SAMPLE_RATE) ?: Long.MAX_VALUE
                while (running.get() && position < limit) {
                    val count = audio.read(block, 0, minOf(block.size.toLong(), limit - position).toInt(), AudioRecord.READ_BLOCKING)
                    if (count < 0) {
                        check(!running.get()) { "读取系统播放声音失败（$count），请重新开始录制并授权。" }
                        break
                    }
                    if (count == 0) continue
                    val samples = FloatArray(count) { block[it] / 32768f }
                    val result = trySend(PcmFrame(samples, position))
                    check(result.isSuccess || !running.get() || result.isClosed) { "音频处理队列已满，录制已停止。" }
                    position += count
                }
                close()
            } catch (e: Throwable) { close(e) }
            finally {
                synchronized(lock) {
                    if (local?.recordingState == AudioRecord.RECORDSTATE_RECORDING) local.stop()
                    local?.release()
                    recorder = null
                }
            }
        }
        awaitClose { stop(); worker.join(2000) }
    }.buffer(256)
}
