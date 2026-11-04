package com.opensub.app.testing

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import org.json.JSONObject
import java.io.File

/** Debug build only: plays the user-provided local test file, with no automatic playback. */
class TestVideoActivity : Activity() {
    private lateinit var video: VideoView
    private val handler = Handler(Looper.getMainLooper())
    private val report = object : Runnable {
        override fun run() {
            File(filesDir, "test-video-playback.json").writeText(JSONObject().put("positionMs", video.currentPosition)
                .put("durationMs", video.duration).put("playing", video.isPlaying).put("timestampMs", android.os.SystemClock.elapsedRealtime()).toString())
            handler.postDelayed(this, 500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(filesDir, "test-video.mp4")
        check(file.isFile) { "尚未导入测试视频" }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this).apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
        }
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val controller = MediaController(this)
        video.setMediaController(controller)
        controller.setAnchorView(video)
        val back = Button(this).apply { text = "返回字幕控制"; setOnClickListener { finish() } }
        root.addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = (24 * resources.displayMetrics.density).toInt()
        })
        setContentView(root)
        video.setOnPreparedListener {
            video.seekTo(savedInstanceState?.getInt("position") ?: 60_000)
            controller.show(0)
        }
        video.setVideoPath(file.absolutePath)
        handler.post(report)
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("position", video.currentPosition); super.onSaveInstanceState(outState) }
    override fun onPause() { video.pause(); super.onPause() }
    override fun onDestroy() { handler.removeCallbacks(report); video.stopPlayback(); super.onDestroy() }
}
