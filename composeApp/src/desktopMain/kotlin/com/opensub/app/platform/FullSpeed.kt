package com.opensub.app.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

private interface ProcessApi : StdCallLibrary {
    fun GetCurrentProcess(): Pointer
    fun SetProcessInformation(process: Pointer, informationClass: Int, information: Pointer, size: Int): Boolean
}

private const val PROCESS_POWER_THROTTLING = 4
private const val POWER_THROTTLING_VERSION = 1
private const val EXECUTION_SPEED = 1

/**
 * Asks Windows never to run this process as background work. Without it Windows ran a replay, which has no window, as
 * background work on the CPU's efficiency cores: a speech reading took 0.24 s instead of 0.12 s, every run, and English came
 * up 0.17 s later. The user watches a video with OpenSub behind it. `hy-translate` asks the same; it showed no difference in
 * replays. Returns whether Windows took the request.
 */
fun keepFullSpeed(): Boolean = try {
    val api = Native.load("kernel32", ProcessApi::class.java)
    val state = Memory(12).apply { setInt(0, POWER_THROTTLING_VERSION); setInt(4, EXECUTION_SPEED); setInt(8, 0) }
    api.SetProcessInformation(api.GetCurrentProcess(), PROCESS_POWER_THROTTLING, state, 12)
} catch (e: Throwable) { false }
