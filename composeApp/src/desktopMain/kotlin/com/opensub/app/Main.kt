package com.opensub.app

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.opensub.app.platform.AppPaths
import com.opensub.app.platform.keepFullSpeed
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    Thread.setDefaultUncaughtExceptionHandler { _, error ->
        File(AppPaths.logs, "uncaught-error.txt").writeText(error.stackTraceToString())
        error.printStackTrace()
    }
    // Before anything runs, so the diagnostics measure the speed the app gets.
    keepFullSpeed()
    // Left to escape main, a failed diagnostic puts up the packaged launcher's "Failed to launch JVM" dialog in front of the user.
    try {
        when {
            "--self-test" in args -> { runBlocking { Diagnostics.selfTest() }; return }
            "--capture-test" in args -> { runBlocking { Diagnostics.captureTest("--play-sample" in args) }; return }
            "--probe-audio" in args -> { runBlocking { Diagnostics.probeAudio() }; return }
            "--verify-recording" in args -> { runBlocking { Diagnostics.verifyRecording() }; return }
            "--latency-benchmark" in args -> { runBlocking { Diagnostics.latencyBenchmark() }; return }
            "--speech-evaluation" in args -> { runBlocking { speechEvaluation(args.last()) }; return }
            "--recognition-replay" in args -> { runBlocking { recognitionReplay(args[1], args[2].toInt()) }; return }
            "--transcript-score" in args -> { runBlocking { transcriptScore(args[1], args[2].toInt(), args.drop(3)) }; return }
            "--speaker-probe" in args -> { speakerProbe(args[1], args.getOrElse(2) { "" }); return }
            "--translate-file" in args -> { runBlocking { translateFile(args.last()) }; return }
            "--soak-test" in args -> { runBlocking { soakTest(3) }; return }
            "--translation-test" in args -> { runBlocking { translationTest() }; return }
            "--ui-preview" in args -> { renderUiPreview(); kotlin.system.exitProcess(0) }
            "--animation-preview" in args -> { renderAnimationPreview(); return }
        }
    } catch (error: Throwable) {
        File(AppPaths.logs, "uncaught-error.txt").writeText(error.stackTraceToString())
        error.printStackTrace()
        kotlin.system.exitProcess(1)
    }
    application {
        val controller = remember { DesktopController() }
        val state by controller.state.collectAsState()
        val settings by controller.settings.collectAsState()
        val cue by controller.cue.collectAsState()
        val chinese by controller.chinese.collectAsState()
        Window(
            title = "OpenSub",
            onCloseRequest = { controller.close(); exitApplication() },
            state = rememberWindowState(width = 720.dp, height = 620.dp),
        ) {
            window.minimumSize = java.awt.Dimension(560, 520)
            OpenSubApp(state, settings, controller)
        }
        SubtitleWindow(settings, cue, chinese, controller)
    }
}
