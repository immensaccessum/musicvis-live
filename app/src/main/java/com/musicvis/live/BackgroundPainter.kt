package com.musicvis.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.musicvis.live.audio.AudioEngine

/**
 * Optional wallpaper background: a heavily darkened vertical gradient built
 * from the user's bar palette that "breathes" with the music (quiet = darker).
 * Disabled (plain color) by default.
 */
class BackgroundPainter(private val context: Context) {
    private val paint = Paint()
    private var key: String? = null
    private var w = 0
    private var h = 0
    private var level = 0f

    /** Draws the gradient when enabled, otherwise fills with [fallback]. */
    fun draw(canvas: Canvas, audio: AudioEngine, fallback: Int) {
        if (!drawIfEnabled(canvas, audio)) canvas.drawColor(fallback)
    }

    /** Draws the gradient if the feature is on; returns whether it drew. */
    fun drawIfEnabled(canvas: Canvas, audio: AudioEngine): Boolean {
        if (!FeaturePrefs.bgGradient(context)) return false
        val ck = HistogramColors.textureKey(context)
        if (ck != key || canvas.width != w || canvas.height != h) {
            key = ck
            w = canvas.width
            h = canvas.height
            val c = HistogramColors.loadEffective(context)
            paint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(dark(c[0]), dark(c[1]), dark(c[2])),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        // Breathing: a black veil that thins out as the music gets louder.
        level = level * 0.92f + audio.rms.coerceIn(0f, 1f) * 0.08f
        val a = ((1f - level.coerceIn(0f, 1f)) * 130f).toInt().coerceIn(0, 255)
        if (a > 0) canvas.drawColor(Color.argb(a, 0, 0, 0))
        return true
    }

    companion object {
        /** Palette color -> deep background shade (keeps the hue). */
        fun dark(c: Int): Int = Color.rgb(
            (Color.red(c) * 0.16f).toInt(),
            (Color.green(c) * 0.16f).toInt(),
            (Color.blue(c) * 0.16f).toInt()
        )
    }
}
