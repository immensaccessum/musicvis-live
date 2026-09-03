package com.musicvis.live.wallpapers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.musicvis.live.HistogramColors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Spectrogram waterfall: newest FFT line on top, time scrolls down.
 * X = log frequency, brightness/color = energy through the user gradient.
 */
class WaterfallService : VisWallpaperService() {
    private val cols = 192
    private val rows = 384
    private val model = IntArray(cols * rows)
    private val bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = true }
    private val src = Rect(0, 0, cols, rows)
    private val dst = Rect()
    private var palette = IntArray(0)
    private var palKey: String? = null
    private var phase = 0

    override fun paint(canvas: Canvas, env: PaintEnv) {
        val key = HistogramColors.textureKey(this)
        if (key != palKey) {
            palKey = key
            palette = HistogramColors.palette(this, 256)
        }

        // Scroll every other frame so 120 Hz doesn't rush the history away.
        phase++
        if (phase % 2 == 0) {
            System.arraycopy(model, 0, model, cols, cols * (rows - 1))
            fillTopRow(env)
            bmp.setPixels(model, 0, cols, 0, 0, cols, rows)
        }

        canvas.drawColor(Color.BLACK)
        dst.set(0, 0, canvas.width, canvas.height)
        canvas.drawBitmap(bmp, src, dst, paint)

        env.trackLine?.let { title ->
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 230, 230, 255)
                textSize = 36f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(title, canvas.width / 2f, canvas.height - 80f, tp)
        }
    }

    private fun fillTopRow(env: PaintEnv) {
        val raw = env.audio.fftRaw
        if (raw.size < 8 || env.audio.audioIdle) {
            java.util.Arrays.fill(model, 0, cols, palette.firstOrNull()?.let { dim(it) } ?: Color.BLACK)
            return
        }
        val bins = raw.size / 2
        val lmin = ln(2f)
        val lmax = ln((bins - 1).toFloat())
        val half = palette.size / 2
        for (x in 0 until cols) {
            val b = exp(lmin + (lmax - lmin) * x / (cols - 1)).toInt().coerceIn(2, bins - 1)
            val re = raw[b * 2].toInt()
            val im = raw[b * 2 + 1].toInt()
            val m = (sqrt((re * re + im * im).toFloat()) / 170f).coerceIn(0f, 1f)
            // 0 -> black, 1 -> center (brightest) of the gradient
            val c = palette[(m * (half - 1)).toInt().coerceIn(0, palette.size - 1)]
            val v = m.coerceIn(0f, 1f)
            model[x] = Color.rgb(
                (Color.red(c) * v).toInt(),
                (Color.green(c) * v).toInt(),
                (Color.blue(c) * v).toInt()
            )
        }
    }

    private fun dim(c: Int): Int =
        Color.rgb(Color.red(c) / 8, Color.green(c) / 8, Color.blue(c) / 8)
}
