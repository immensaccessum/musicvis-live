package com.musicvis.live.wallpapers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.musicvis.live.PaletteCache
import kotlin.math.sin

/**
 * Oscilloscope: raw PCM as a thin bright line with a soft glow,
 * like a scope screen. X = time within the capture, Y = amplitude.
 */
class ScopeService : VisWallpaperService() {
    private val path = Path()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(40, 120, 160, 200)
        strokeWidth = 2f
    }
    private val pal = PaletteCache(8)

    override fun paint(canvas: Canvas, env: PaintEnv) {
        if (pal.refresh(this)) {
            val mid = pal.colors[pal.colors.size / 2]
            line.color = mid
            glow.color = Color.argb(60, Color.red(mid), Color.green(mid), Color.blue(mid))
        }

        env.bg.draw(canvas, env.audio, Color.rgb(3, 6, 10))
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        // scope grid
        for (i in 1 until 8) {
            val y = h * i / 8f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        for (i in 1 until 6) {
            val x = w * i / 6f
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }

        val mid = h / 2f + env.tiltY * 20f
        path.reset()
        val mix = env.audio.idleMix()
        val pcm = env.audio.pcm
        val n = pcm.size
        val t = env.timeMs * 0.002f
        val step = if (env.preview) 8 else 2
        var first = true
        var i = 0
        while (i < n) {
            val x = i * w / (n - 1)
            val yLive = mid + pcm[i] / 128f * h * 0.28f
            val fi = i * 256f / (n - 1)
            val yIdle = mid + sin(fi * 0.09f + t) * sin(fi * 0.017f - t * 0.6f) * h * 0.08f
            val y = yLive * (1f - mix) + yIdle * mix
            if (first) {
                path.moveTo(x, y)
                first = false
            } else path.lineTo(x, y)
            i += step
        }
        canvas.drawPath(path, glow)
        canvas.drawPath(path, line)
    }
}
