package com.opensub.app.display

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.opensub.app.R
import com.opensub.app.core.SubtitleCue
import com.opensub.app.core.SubtitleFont
import com.opensub.app.core.SubtitleMode
import com.opensub.app.core.SubtitleStyle
import com.opensub.app.core.WordReveal
import com.opensub.app.core.balancedRows
import com.opensub.app.core.filledRows
import com.opensub.app.core.fitChinese
import com.opensub.app.core.subtitlePunctuation
import kotlin.math.roundToInt

// ClaudeColors.Text, Secondary and Caption, as platform colour ints for the native view.
private const val CAPTION_TEXT = 0xFFFAF9F5.toInt()
private const val CAPTION_SECONDARY = 0xFFC2C0B6.toInt()
private const val CAPTION_CARD = 0xFF1F1E1D.toInt()
private const val RESERVED_LINES = 2
// Average Latin character width as a share of font size; a little generous so lines rarely wrap.
private const val LATIN_WIDTH = 0.52f
private const val LONGEST_LINE_CHARS = 42
private const val LONGEST_CHINESE_CHARS = 22

/** Native overlay UI, using the exact same word-reveal state machine as desktop. */
class AndroidSubtitleOverlay(private val context: Context, private val onError: (Throwable) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(WindowManager::class.java)
    private val reveal = WordReveal()
    private val density = context.resources.displayMetrics.density
    private val view = TextView(context).apply {
        setTextColor(CAPTION_TEXT); includeFontPadding = false; setLineSpacing(0f, 1.3f); gravity = Gravity.CENTER_HORIZONTAL
    }
    private val chineseView = TextView(context).apply {
        setTextColor(CAPTION_TEXT); includeFontPadding = false; setLineSpacing(0f, 1.3f); gravity = Gravity.CENTER_HORIZONTAL
    }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        addView(view); addView(chineseView)
    }
    private var mode = SubtitleMode.Bilingual
    private var wordColor = CAPTION_TEXT
    private var chineseChars = 20
    private var style = SubtitlePreferences.read(context)
    private val params = WindowManager.LayoutParams(
        cardWidth(), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
    private var attached = false
    private var closed = false
    private var currentText = ""
    private var span = SpannableString("")
    private val tick = object : Runnable {
        override fun run() {
            if (closed || !attached) return
            val frame = reveal.frame(SystemClock.uptimeMillis())
            // Rows are filled in place, so the reveal's word positions still hold.
            val rows = filledRows(frame.text, englishRowChars())
            if (currentText != rows) {
                currentText = rows; span = SpannableString(rows)
                view.setText(span, TextView.BufferType.SPANNABLE)
            }
            val buffer = view.text as Spannable
            buffer.getSpans(0, buffer.length, ForegroundColorSpan::class.java).forEach { buffer.removeSpan(it) }
            buffer.setSpan(ForegroundColorSpan(Color.TRANSPARENT), 0, buffer.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            for (word in frame.words) buffer.setSpan(ForegroundColorSpan(Color.argb((word.opacity * 255).roundToInt(), Color.red(wordColor), Color.green(wordColor), Color.blue(wordColor))), word.start, word.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (frame.animating) handler.postDelayed(this, 16)
        }
    }
    init {
        val metrics = context.resources.displayMetrics
        val saved = SubtitlePreferences.position(context)
        params.y = saved?.second ?: (metrics.heightPixels * 0.72f).toInt()
        applyStyle(style)
        var startY = 0; var downY = 0f
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = params.y; downY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    // Subtitles stay centred; dragging only chooses their height.
                    params.y = startY + (event.rawY - downY).toInt()
                    clampPosition(); manager.updateViewLayout(root, params); true
                }
                MotionEvent.ACTION_UP -> { SubtitlePreferences.savePosition(context, params.x, params.y); root.performClick(); true }
                else -> true
            }
        }
    }
    fun show(cue: SubtitleCue) = handler.post {
        if (closed) return@post
        try {
            check(Settings.canDrawOverlays(context)) { "悬浮窗权限已关闭，字幕已停止。" }
            reveal.target(cue.id, cue.english, SystemClock.uptimeMillis())
            if (mode == SubtitleMode.Chinese) return@post
            attach()
            handler.removeCallbacks(tick); tick.run()
            relayout()
        } catch (e: Throwable) { onError(e) }
    }
    /** A sentence's Chinese can land after the next preview has begun, so it stays apart from the English reveal. */
    fun showTranslation(cue: SubtitleCue) = handler.post {
        if (closed || mode == SubtitleMode.English) return@post
        try {
            attach()
            chineseView.text = fitChinese(cue.chinese, chineseChars * RESERVED_LINES)
            relayout()
        } catch (e: Throwable) { onError(e) }
    }
    /**
     * One sentence, as Windows shows it ([com.opensub.app.core.SentenceCaption]): its English as heard, in rows, with its own
     * Chinese beneath. A new [sentence] starts the English reveal over.
     */
    fun showSentence(sentence: Long, english: String, chinese: String) = handler.post {
        if (closed) return@post
        try {
            check(Settings.canDrawOverlays(context)) { "悬浮窗权限已关闭，字幕已停止。" }
            reveal.target(sentence, english, SystemClock.uptimeMillis())
            // Chinese alone shows nothing until a sentence has some.
            if (mode == SubtitleMode.Chinese && chinese.isEmpty()) return@post
            attach()
            if (mode != SubtitleMode.Chinese) { handler.removeCallbacks(tick); tick.run() }
            if (mode != SubtitleMode.English) chineseView.text = subtitlePunctuation(balancedRows(chinese, chineseRowChars()))
            relayout()
        } catch (e: Throwable) { onError(e) }
    }
    /** How many English characters fit one line of the card at the current size. */
    fun englishLineChars(): Int = (textWidth() / (view.textSize * LATIN_WIDTH)).toInt()
    /** One row of English, no longer than a film subtitle's (Netflix keeps English to 42 characters). */
    fun englishRowChars(): Int = englishLineChars().coerceAtMost(LONGEST_LINE_CHARS)
    /** One row of Chinese, as on Windows. */
    fun chineseRowChars(): Int = chineseChars.coerceAtMost(LONGEST_CHINESE_CHARS)
    private fun cardWidth() = (context.resources.displayMetrics.widthPixels * 0.9f).toInt()
    private fun textWidth() = cardWidth() - root.paddingLeft - root.paddingRight
    private fun attach() { if (!attached) { clampPosition(); manager.addView(root, params); attached = true } }
    private fun relayout() { root.post { if (attached) { clampPosition(); manager.updateViewLayout(root, params) } } }
    fun updateStyle(style: SubtitleStyle) = handler.post {
        applyStyle(style)
        relayout()
    }
    private fun applyStyle(style: SubtitleStyle) {
        this.style = style
        mode = style.mode
        val bilingual = mode == SubtitleMode.Bilingual
        wordColor = if (bilingual) CAPTION_SECONDARY else CAPTION_TEXT
        view.textSize = style.englishFontSize
        // Reserving both lines keeps the card one size, whatever is on it.
        view.minLines = RESERVED_LINES
        view.visibility = if (mode == SubtitleMode.Chinese) View.GONE else View.VISIBLE
        chineseView.textSize = style.fontSize
        chineseView.minLines = RESERVED_LINES; chineseView.maxLines = RESERVED_LINES
        chineseChars = (textWidth() / chineseView.textSize).toInt()
        val typeface = if (style.font == SubtitleFont.Serif) context.resources.getFont(R.font.noto_serif_sc) else Typeface.SANS_SERIF
        view.typeface = typeface; chineseView.typeface = typeface
        root.removeAllViews()
        if (style.chineseFirst) { root.addView(chineseView); root.addView(view) } else { root.addView(view); root.addView(chineseView) }
        chineseView.visibility = if (mode == SubtitleMode.English) View.GONE else View.VISIBLE
        if (attached) { currentText = ""; handler.removeCallbacks(tick); tick.run() }
        root.background = GradientDrawable().apply { cornerRadius = 12 * density; setColor(Color.argb((style.backgroundOpacity * 255).toInt(), Color.red(CAPTION_CARD), Color.green(CAPTION_CARD), Color.blue(CAPTION_CARD))) }
    }
    private fun clampPosition() {
        val metrics = context.resources.displayMetrics
        params.width = cardWidth()
        params.y = params.y.coerceIn(0, (metrics.heightPixels - root.height.coerceAtLeast((100 * density).toInt())).coerceAtLeast(0))
    }
    /**
     * The screen turned: the card is 90% of the new width, so how many characters fit a row changes with it. Upright, the
     * Chinese row kept the landscape count and the second line was cut off. The next sentence lays out with the new counts.
     */
    fun resize() = handler.post {
        applyStyle(style)
        if (attached) { clampPosition(); manager.updateViewLayout(root, params) }
    }
    fun clear() = handler.post {
        handler.removeCallbacks(tick)
        if (attached) { manager.removeView(root); attached = false }
        reveal.target(null, "", SystemClock.uptimeMillis())
        chineseView.text = ""
    }
    fun close() = handler.post {
        closed = true; handler.removeCallbacks(tick)
        if (attached) { manager.removeView(root); attached = false }
    }
}
