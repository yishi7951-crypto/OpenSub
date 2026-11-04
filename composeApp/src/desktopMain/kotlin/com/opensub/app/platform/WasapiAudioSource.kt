package com.opensub.app.platform

import com.opensub.app.core.*
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class MixFormat(val sampleRate: Int, val channels: Int, val bits: Int, val encoding: Int, val blockAlign: Int, val systemMuted: Boolean = false) {
    override fun toString() = "$sampleRate Hz · $channels 声道 · ${if (encoding == 3) "Float" else "PCM"}$bits${if (systemMuted) " · 系统已静音" else ""}"
}

/** WASAPI shared-mode render loopback. Every COM call lives on one dedicated OS thread. */
class WasapiAudioSource(
    private val durationSeconds: Int? = null,
    private val onFormat: (MixFormat) -> Unit = {},
    private val onRaw: (MixFormat, ByteArray) -> Unit = { _, _ -> },
    private val onMuteChanged: (Boolean) -> Unit = {},
) : AudioSource {
    private val running = AtomicBoolean(true)
    override fun stop() { running.set(false) }
    override fun frames(): Flow<PcmFrame> = callbackFlow {
        val worker = thread(name = "OpenSub-WASAPI", isDaemon = true) {
            try {
                WasapiSession().use { capture ->
                    val format = capture.format
                    onFormat(format)
                    var muted = format.systemMuted
                    onMuteChanged(muted)
                    var nextMutePoll = capture.start100ns + 10_000_000
                    val converter = StreamingResampler(format.sampleRate)
                    var inputFrames = 0L
                    var outputSamples = 0L
                    val limit = durationSeconds?.toLong()?.times(format.sampleRate) ?: Long.MAX_VALUE
                    fun process(bytes: ByteArray) {
                        val available = bytes.size / format.blockAlign
                        val count = minOf(available.toLong(), limit - inputFrames).toInt()
                        if (count <= 0) return
                        val raw = if (count == available) bytes else bytes.copyOf(count * format.blockAlign)
                        onRaw(format, raw)
                        val mono = FloatArray(count)
                        val stride = format.bits / 8
                        for (frame in 0 until count) {
                            var sum = 0f
                            for (channel in 0 until format.channels) {
                                val offset = frame * format.blockAlign + channel * stride
                                fun b(index: Int) = raw[offset + index].toInt() and 255
                                sum += when {
                                    format.encoding == 3 && format.bits == 32 -> Float.fromBits(b(0) or (b(1) shl 8) or (b(2) shl 16) or (b(3) shl 24))
                                    format.bits == 16 -> (b(0) or (b(1) shl 8)).toShort() / 32768f
                                    format.bits == 24 -> ((b(0) shl 8) or (b(1) shl 16) or (b(2) shl 24)) / 2147483648f
                                    format.bits == 32 -> (b(0) or (b(1) shl 8) or (b(2) shl 16) or (b(3) shl 24)) / 2147483648f
                                    else -> error("不支持的系统音频格式：$format")
                                }
                            }
                            mono[frame] = (sum / format.channels).coerceIn(-1f, 1f)
                        }
                        inputFrames += count
                        val samples = converter.accept(mono)
                        if (samples.isNotEmpty()) {
                            val result = trySend(PcmFrame(samples, outputSamples))
                            check(result.isSuccess || !running.get() || result.isClosed) { "音频处理队列已满，录音已停止。" }
                            outputSamples += samples.size
                        }
                    }
                    fun fillSilence(until: Long) {
                        val end = minOf(until, limit)
                        while (inputFrames < end && running.get()) {
                            process(ByteArray(minOf(end - inputFrames, format.sampleRate / 50L).toInt() * format.blockAlign))
                        }
                    }
                    while (running.get() && inputFrames < limit) {
                        val now = WinClock.now100ns()
                        if (now >= nextMutePoll) {
                            val currentMute = capture.isMuted()
                            if (currentMute != muted) { muted = currentMute; onMuteChanged(muted) }
                            nextMutePoll = now + 10_000_000
                        }
                        var received = false
                        while (capture.packetSize() > 0 && running.get() && inputFrames < limit) {
                            val packet = capture.read()
                            if (packet.position100ns != null) {
                                fillSilence(((packet.position100ns - capture.start100ns) * format.sampleRate / 10_000_000).coerceAtLeast(0))
                            }
                            process(packet.bytes)
                            received = true
                        }
                        if (!received) {
                            // A silent render device may not deliver packets. A 50 ms cushion avoids
                            // inserting silence in front of a normally buffered audio packet.
                            fillSilence(((WinClock.now100ns() - capture.start100ns - 500_000) * format.sampleRate / 10_000_000).coerceAtLeast(0))
                            Thread.sleep(5)
                        }
                    }
                }
                close()
            } catch (e: Throwable) { close(e) }
        }
        awaitClose { running.set(false); worker.join(2000) }
    }.buffer(256)
}

private interface WinClockApi : StdCallLibrary {
    fun QueryPerformanceCounter(result: LongByReference): Boolean
    fun QueryPerformanceFrequency(result: LongByReference): Boolean
}
private object WinClock {
    private val api = Native.load("kernel32", WinClockApi::class.java)
    private val frequency = LongByReference().also { check(api.QueryPerformanceFrequency(it)) }.value
    fun now100ns(): Long = LongByReference().also { check(api.QueryPerformanceCounter(it)) }.value.let { (it.toDouble() * 10_000_000 / frequency).toLong() }
}

private class ComObject(val pointer: Pointer) : AutoCloseable {
    fun call(slot: Int, vararg args: Any?): Int {
        val entry = pointer.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(entry, Function.ALT_CONVENTION).invokeInt(arrayOf(pointer, *args))
    }
    fun checked(slot: Int, operation: String, vararg args: Any?) = checkHr(call(slot, *args), operation)
    override fun close() { call(2) }
}

private fun checkHr(code: Int, operation: String) {
    check(code >= 0) { "$operation 失败（WASAPI 0x${Integer.toHexString(code)}）。请检查默认播放设备是否可用；切换耳机后请停止并重新开始。" }
}
private data class AudioPacket(val bytes: ByteArray, val position100ns: Long?)

private class WasapiSession : AutoCloseable {
    private val ole = Ole32.INSTANCE
    private var enumerator: ComObject? = null
    private var device: ComObject? = null
    private var client: ComObject? = null
    private var capture: ComObject? = null
    private var initialized = false
    private var started = false
    val format: MixFormat
    val start100ns: Long
    init {
        try {
            checkHr(ole.CoInitializeEx(null, 0).toInt(), "初始化音频线程")
            initialized = true
            val ref = PointerByReference()
            checkHr(ole.CoCreateInstance(Guid.GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}"), null, 1,
                Guid.GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}"), ref).toInt(), "打开系统音频")
            enumerator = ComObject(ref.value)
            enumerator!!.checked(4, "查找默认播放设备", 0, 1, ref)
            device = ComObject(ref.value)
            val muted = readMute(device!!)
            device!!.checked(3, "打开环回录音", Guid.GUID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}"), 1, null, ref)
            client = ComObject(ref.value)
            client!!.checked(8, "读取系统音频格式", ref)
            val wave = ref.value
            try {
                val tag = wave.getShort(0).toInt() and 65535
                val encoding = if (tag == 65534) wave.getInt(24) else tag
                format = MixFormat(wave.getInt(4), wave.getShort(2).toInt() and 65535,
                    wave.getShort(14).toInt() and 65535, encoding, wave.getShort(12).toInt() and 65535, muted)
                check((encoding == 3 && format.bits == 32) || (encoding == 1 && format.bits in listOf(16, 24, 32))) { "不支持的系统音频格式：$format" }
                // IAudioClient::Initialize: shared mode, AUDCLNT_STREAMFLAGS_LOOPBACK.
                client!!.checked(3, "初始化环回录音", 0, 0x00020000, 1_000_000L, 0L, wave, null)
            } finally { ole.CoTaskMemFree(wave) }
            client!!.checked(14, "获取环回缓冲区", Guid.GUID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}"), ref)
            capture = ComObject(ref.value)
            start100ns = WinClock.now100ns()
            client!!.checked(10, "开始环回录音")
            started = true
        } catch (e: Throwable) { close(); throw e }
    }
    fun packetSize(): Int {
        val size = IntByReference()
        capture!!.checked(5, "读取音频包长度", size)
        return size.value
    }
    fun isMuted(): Boolean = readMute(device!!)
    fun read(): AudioPacket {
        val data = PointerByReference()
        val frames = IntByReference()
        val flags = IntByReference()
        val clock = LongByReference()
        capture!!.checked(3, "读取系统声音", data, frames, flags, null, clock)
        try {
            val length = frames.value * format.blockAlign
            val bytes = if (flags.value and 2 != 0) ByteArray(length) else data.value.getByteArray(0, length)
            return AudioPacket(bytes, if (flags.value and 4 == 0) clock.value else null)
        } finally { capture!!.checked(4, "释放音频缓冲区", frames.value) }
    }
    override fun close() {
        if (started) { client?.call(11); started = false }
        capture?.close(); capture = null
        client?.close(); client = null
        device?.close(); device = null
        enumerator?.close(); enumerator = null
        if (initialized) { ole.CoUninitialize(); initialized = false }
    }
}

private fun readMute(device: ComObject): Boolean {
    val ref = PointerByReference()
    device.checked(3, "读取系统音量状态", Guid.GUID("{5CDF2C82-841E-4546-9722-0CF74078229A}"), 1, null, ref)
    return ComObject(ref.value).use { volume ->
        val muted = IntByReference()
        volume.checked(15, "读取系统静音状态", muted)
        muted.value != 0
    }
}
