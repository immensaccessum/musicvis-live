package com.musicvis.live.wallpapers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * Analog VU needle (vis4 spirit): smoothed loudness drives the needle,
 * peak lamp lights on transients.
 */
class VuMeterService : VisWallpaperService() {
    private var needle = 0f
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.rgb(160, 180, 210)
    }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.rgb(229, 57, 53)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        color = Color.rgb(150, 165, 195)
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(255, 235, 200)
    }
    private val lampPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 205, 230)
        textAlign = Paint.Align.CENTER
    }

    override fun paint(canvas: Canvas, env: PaintEnv) {
        val audio = env.audio
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        env.bg.draw(canvas, env.audio, Color.rgb(8, 10, 16))

        val target = if (audio.audioIdle) {
            0.08f + 0.04f * sin(env.timeMs / 900f)
        } else {
            audio.rms.coerceIn(0f, 1f)
        }
        // VU ballistics: fast attack, slow release
        needle += if (target > needle) (target - needle) * 0.25f else (target - needle) * 0.06f

        val cx = w / 2f + env.tiltX * w * 0.02f
        val cy = h * 0.62f
        val r = minOf(w, h) * 0.42f

        // Scale arc: -55°..+55° from vertical; last 20% is the red zone
        val start = 180f + 35f
        val sweep = 110f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, start, sweep * 0.8f, false, arcPaint)
        canvas.drawArc(rect, start + sweep * 0.8f, sweep * 0.2f, false, redPaint)

        for (t in 0..10) {
            val a = Math.toRadians((start + sweep * t / 10f).toDouble())
            val inner = if (t % 5 == 0) r - 44f else r - 26f
            canvas.drawLine(
                cx + (cos(a) * inner).toFloat(), cy + (sin(a) * inner).toFloat(),
                cx + (cos(a) * (r - 8f)).toFloat(), cy + (sin(a) * (r - 8f)).toFloat(),
                tickPaint
            )
        }

        val na = Math.toRadians((start + sweep * needle.coerceIn(0f, 1f)).toDouble())
        canvas.drawLine(
            cx, cy,
            cx + (cos(na) * (r - 30f)).toFloat(), cy + (sin(na) * (r - 30f)).toFloat(),
            needlePaint
        )
        canvas.drawCircle(cx, cy, 20f, needlePaint)

        lampPaint.color = if (audio.peak) Color.rgb(255, 70, 60) else Color.rgb(60, 24, 22)
        canvas.drawCircle(cx + r * 0.72f, cy - r * 0.72f, 22f + (if (audio.peak) 6f else 0f), lampPaint)

        textPaint.textSize = 64f
        canvas.drawText("VU", cx, cy - r * 0.35f, textPaint)
        env.trackLine?.let {
            textPaint.textSize = 36f
            canvas.drawText(it, w / 2f, h - 80f, textPaint)
        }
    }
}
