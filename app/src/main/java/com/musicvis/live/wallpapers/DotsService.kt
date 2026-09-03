package com.musicvis.live.wallpapers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.musicvis.live.HistogramColors
import kotlin.math.sin

/**
 * Dot swarm (vis5 spirit): recent waveforms recede into the distance
 * as rows of points, newest at the bottom front.
 */
class DotsService : VisWallpaperService() {
    private val history = ArrayDeque<FloatArray>()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private var palette = IntArray(0)
    private var palKey: String? = null
    private var phase = 0

    override fun paint(canvas: Canvas, env: PaintEnv) {
        val key = HistogramColors.textureKey(this)
        if (key != palKey) {
            palKey = key
            palette = HistogramColors.palette(this, 128)
        }
        phase++
        if (phase % 2 == 0) {
            val src = env.audio.waveform
            val copy = if (env.audio.audioIdle) idleRow(env.timeMs) else src.copyOf()
            history.addLast(copy)
            while (history.size > ROWS) history.removeFirst()
        }

        env.bg.draw(canvas, env.audio, Color.rgb(4, 6, 12))
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val half = palette.size / 2

        var row = 0
        for (wave in history) {
            // oldest row first: smaller, higher, dimmer
            val t = row.toFloat() / (history.size.coerceAtLeast(2) - 1)
            val scale = 0.45f + 0.55f * t
            val mid = h * (0.30f + 0.42f * t) + env.tiltY * 24f * t
            val alpha = (30 + 225 * t * t).toInt()
            val radius = 2f + 4f * t
            val n = wave.size
            for (i in 0 until n step 2) {
                val x = w / 2f + (i.toFloat() / (n - 1) - 0.5f) * w * scale + env.tiltX * w * 0.02f * t
                val amp = (wave[i] - 0.5f) * 2f
                val y = mid + amp * h * 0.16f * scale
                val ci = (half + amp * (half - 1)).toInt().coerceIn(0, palette.size - 1)
                dotPaint.color = palette[ci]
                dotPaint.alpha = alpha
                canvas.drawCircle(x, y, radius, dotPaint)
            }
            row++
        }

        env.trackLine?.let { title ->
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 220, 225, 255)
                textSize = 36f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(title, w / 2f, h - 80f, tp)
        }
    }

    private fun idleRow(timeMs: Long): FloatArray {
        val out = FloatArray(128)
        for (i in out.indices) {
            out[i] = 0.5f + 0.18f * sin(i * 0.11f + timeMs * 0.0012f) *
                sin(i * 0.023f + timeMs * 0.0007f)
        }
        return out
    }

    companion object {
        private const val ROWS = 22
    }
}
