package com.musicvis.live.wallpapers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.musicvis.live.HistogramColors
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * "Light show": the whole screen works as a color lamp. Bass lights the
 * bottom, mids the center, highs the top; a core pulses with loudness.
 * Made to be seen across the room, not studied up close.
 */
class LightShowService : VisWallpaperService() {
    private var palette = IntArray(0)
    private var palKey: String? = null
    private val levels = FloatArray(3)
    private val bgPaint = Paint()
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shaderKey = 0
    private var shaderH = 0
    private var idlePhase = 0

    override fun paint(canvas: Canvas, env: PaintEnv) {
        val audio = env.audio
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        val key = HistogramColors.textureKey(this)
        if (key != palKey) {
            palKey = key
            palette = HistogramColors.palette(this, 256)
            shaderKey = 0
        }
        if (palette.isEmpty()) return

        // Band energies (spectrum is already volume-scaled and normalized).
        var bass = 0f
        var mid = 0f
        var high = 0f
        if (audio.audioIdle) {
            idlePhase++
            bass = 0.25f + 0.2f * sin(idlePhase * 0.017f)
            mid = 0.25f + 0.2f * sin(idlePhase * 0.023f + 2f)
            high = 0.25f + 0.2f * sin(idlePhase * 0.011f + 4f)
        } else {
            val s = audio.spectrum
            for (i in 0..3) bass = max(bass, s[i])
            for (i in 4..14) mid = max(mid, s[i])
            for (i in 15 until s.size) high = max(high, s[i])
        }
        // Fast attack, slow release per zone.
        levels[0] = if (bass > levels[0]) bass else levels[0] * 0.93f
        levels[1] = if (mid > levels[1]) mid else levels[1] * 0.93f
        levels[2] = if (high > levels[2]) high else levels[2] * 0.93f

        // Rebuild the gradient only when the quantized picture changes.
        val q = (quant(levels[0]) shl 16) or (quant(levels[1]) shl 8) or quant(levels[2])
        if (q != shaderKey || h.toInt() != shaderH) {
            shaderKey = q
            shaderH = h.toInt()
            val cBass = lit(palette[40], levels[0])
            val cMid = lit(palette[128], levels[1])
            val cHigh = lit(palette[215], levels[2])
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(cHigh, cMid, cBass),
                floatArrayOf(0.1f, 0.5f, 0.9f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Loudness core.
        val rms = audio.rms.coerceIn(0f, 1f)
        corePaint.color = Color.argb((40 + rms * 150).toInt().coerceAtMost(255), 255, 255, 255)
        canvas.drawCircle(w / 2f, h / 2f, 40f + rms * w * 0.35f, corePaint)

        env.trackLine?.let { title ->
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 255, 255, 255)
                textSize = 36f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(title, w / 2f, h - 80f, tp)
        }
    }

    private fun quant(v: Float) = (v.coerceIn(0f, 1f) * 24).toInt()

    /** Palette color lit by the zone energy (never fully black). */
    private fun lit(c: Int, level: Float): Int {
        val b = 0.10f + 0.90f * level.coerceIn(0f, 1f).pow(0.75f)
        return Color.rgb(
            (Color.red(c) * b).toInt(),
            (Color.green(c) * b).toInt(),
            (Color.blue(c) * b).toInt()
        )
    }
}
