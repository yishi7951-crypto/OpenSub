package com.opensub.app.display

import android.content.Context
import com.opensub.app.core.SubtitleFont
import com.opensub.app.core.SubtitleMode
import com.opensub.app.core.SubtitleStyle

object SubtitlePreferences {
    private const val NAME = "subtitle-style"
    fun read(context: Context): SubtitleStyle {
        val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return SubtitleStyle(preferences.getFloat("fontSize", 32f).coerceIn(20f, 64f), preferences.getFloat("opacity", 0.75f).coerceIn(0.15f, 1f),
            SubtitleMode.entries.find { it.name == preferences.getString("mode", null) } ?: SubtitleMode.Bilingual,
            SubtitleFont.entries.find { it.name == preferences.getString("font", null) } ?: SubtitleFont.Sans,
            preferences.getBoolean("chineseFirst", false), preferences.getFloat("englishFontSize", 24f).coerceIn(20f, 64f))
    }
    fun write(context: Context, style: SubtitleStyle) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putFloat("fontSize", style.fontSize)
            .putFloat("opacity", style.backgroundOpacity).putString("mode", style.mode.name)
            .putString("font", style.font.name).putBoolean("chineseFirst", style.chineseFirst).putFloat("englishFontSize", style.englishFontSize).apply()
    }
    fun position(context: Context): Pair<Int, Int>? {
        val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return if (preferences.contains("x") && preferences.contains("y")) preferences.getInt("x", 0) to preferences.getInt("y", 0) else null
    }
    fun savePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt("x", x).putInt("y", y).apply()
    }
}
