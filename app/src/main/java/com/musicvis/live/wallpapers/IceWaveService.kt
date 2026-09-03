package com.musicvis.live.wallpapers

import android.app.WallpaperColors
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import com.musicvis.live.audio.AudioEngine

class IceWaveService : VisWallpaperService() {
    override fun engineColors(): WallpaperColors? {
        if (Build.VERSION.SDK_INT < 27) return null
        return WallpaperColors.fromBitmap(
            android.graphics.Bitmap.createBitmap(intArrayOf(0xFF041018.toInt(), 0xFF3AA0FF.toInt(), 0xFF9EFFFF.toInt()), 3, 1, android.graphics.Bitmap.Config.ARGB_8888)
        )
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var shaderW = 0
    private var shaderH = 0

    override fun paint(canvas: Canvas, env: PaintEnv) {
        val audio = env.audio
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        if (w.toInt() != shaderW || h.toInt() != shaderH) {
            shaderW = w.toInt()
            shaderH = h.toInt()
            bg.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.parseColor("#FF041018"), Color.parseColor("#FF0A3A55"), Color.parseColor("#FF082028")),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            fill.shader = LinearGradient(
                0f, h * 0.2f, 0f, h,
                intArrayOf(Color.parseColor("#6644DDFF"), Color.parseColor("#00000000")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        if (!env.bg.drawIfEnabled(canvas, audio)) canvas.drawRect(0f, 0f, w, h, bg)
        val ox = env.tiltX * w * 0.03f
        canvas.save()
        canvas.translate(ox, env.tiltY * 12f)
        drawWave(canvas, audio.waveform, w, h, 0.50f, 0.22f, Color.parseColor("#FF9EFFFF"), 5f)
        drawWave(canvas, audio.waveform, w, h, 0.62f, 0.12f, Color.parseColor("#FF3AA0FF"), 3f)
        canvas.restore()
        val spark = audio.rms
        val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((80 + spark * 140).toInt().coerceIn(0, 255), 180, 240, 255)
        }
        canvas.drawCircle(w * 0.5f + ox, h * 0.18f + env.cutoutTop * 0.15f, 18f + spark * 48f, sparkPaint)
        if (env.touchBoost > 0.05f) {
            sparkPaint.color = Color.argb((env.touchBoost * 90).toInt(), 180, 220, 255)
            canvas.drawCircle(env.touchX * w, env.touchY * h, 40f + env.touchBoost * 120f, sparkPaint)
        }
        env.trackLine?.let { title ->
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 200, 230, 255)
                textSize = 36f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(title, w / 2f, h - 80f, tp)
        }
    }

    private fun drawWave(
        canvas: Canvas,
        samples: FloatArray,
        w: Float,
        h: Float,
        mid: Float,
        amp: Float,
        color: Int,
        width: Float
    ) {
        path.reset()
        val n = samples.size
        for (i in 0 until n) {
            val x = i * w / (n - 1)
            val y = h * mid + (samples[i] - 0.5f) * 2f * h * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        wave.color = color
        wave.strokeWidth = width
        canvas.drawPath(path, wave)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        canvas.drawPath(path, fill)
    }
}
