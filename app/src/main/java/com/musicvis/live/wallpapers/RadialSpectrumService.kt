package com.musicvis.live.wallpapers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.musicvis.live.FeaturePrefs
import com.musicvis.live.R
import com.musicvis.live.HistogramColors
import com.musicvis.live.audio.AudioEngine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Radial spectrum: frequency bands around a circle, bar length = energy,
 * inner circle breathes with loudness.
 */
class RadialSpectrumService : VisWallpaperService() {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val smooth = FloatArray(BARS)
    private var palette = IntArray(0)
    private var palKey: String? = null
    private var angle = 0f
    private var lastMs = 0L
    private var dispHz = 0f
    private var noteAlpha = 0f
    private var noteNames: List<String>? = null
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val tunerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }

    override fun paint(canvas: Canvas, env: PaintEnv) {
        val key = HistogramColors.textureKey(this)
        if (key != palKey) {
            palKey = key
            palette = HistogramColors.palette(this, BARS)
        }

        env.bg.draw(canvas, env.audio, Color.rgb(5, 7, 14))
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val cx = w / 2f + env.tiltX * w * 0.03f
        val cy = h / 2f + env.tiltY * h * 0.02f
        val r0 = minOf(w, h) * 0.20f
        val rMax = minOf(w, h) * 0.26f

        val audio = env.audio
        val spectrum = audio.spectrum

        // Slow base rotation that speeds up on loud moments.
        val dt = if (lastMs == 0L) 0f else (env.timeMs - lastMs).coerceAtMost(100L) / 1000f
        lastMs = env.timeMs
        val loud = if (audio.audioIdle) 0f else audio.rms.coerceIn(0f, 1f)
        angle += (0.03f + loud * loud * 0.9f) * dt
        val rot = angle + (env.xOffset - 0.5f) * 0.8f

        for (i in 0 until BARS) {
            val target = if (audio.audioIdle) {
                0.12f + 0.10f * sin(i * 0.35f + env.timeMs * 0.0016f)
            } else {
                sampleBand(spectrum, i)
            }.coerceIn(0f, 1f)
            val old = smooth[i]
            smooth[i] = if (target > old) target else old * 0.90f
            val len = r0 * 0.15f + smooth[i] * rMax

            val a = (i.toFloat() / BARS) * (Math.PI * 2).toFloat() + rot
            val ca = cos(a)
            val sa = sin(a)
            barPaint.color = palette[i]
            canvas.drawLine(
                cx + ca * r0, cy + sa * r0,
                cx + ca * (r0 + len), cy + sa * (r0 + len),
                barPaint
            )
        }

        val pulse = if (audio.audioIdle) 0.1f else audio.rms
        val mid = palette[palette.size / 2]
        corePaint.color = Color.argb(70, Color.red(mid), Color.green(mid), Color.blue(mid))
        canvas.drawCircle(cx, cy, r0 * (0.55f + pulse * 0.35f), corePaint)

        if (FeaturePrefs.noteDisplay(this)) {
            drawNote(canvas, audio, cx, cy, r0, pulse)
        }

        if (env.touchBoost > 0.05f) {
            corePaint.color = Color.argb((env.touchBoost * 80).toInt(), 200, 225, 255)
            canvas.drawCircle(env.touchX * w, env.touchY * h, 40f + env.touchBoost * 140f, corePaint)
        }

        env.trackLine?.let { title ->
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 215, 225, 255)
                textSize = 36f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(title, w / 2f, h - 80f, tp)
        }
    }

    /** Tuner-style overlay: note name in the circle, cents bar below it. */
    private fun drawNote(canvas: Canvas, audio: AudioEngine, cx: Float, cy: Float, r0: Float, pulse: Float) {
        val hz = audio.pitchHz
        val active = hz > 30f && audio.pitchConf > 0.8f && !audio.audioIdle
        noteAlpha += ((if (active) 1f else 0f) - noteAlpha) * 0.12f
        if (active) dispHz = if (dispHz <= 0f) hz else dispHz * 0.7f + hz * 0.3f
        if (noteAlpha < 0.05f || dispHz <= 0f) return

        val midi = (69.0 + 12.0 * ln(dispHz / 440.0) / ln(2.0)).roundToInt()
        val noteFreq = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val cents = (1200.0 * ln(dispHz / noteFreq) / ln(2.0)).roundToInt().coerceIn(-50, 50)
        val idx = ((midi % 12) + 12) % 12
        val octave = midi / 12 - 1
        val names = noteNames ?: getString(R.string.note_names).split(",").also { noteNames = it }
        val name = names.getOrElse(idx) { "?" }

        val a = (noteAlpha * 255).toInt().coerceIn(0, 255)
        val noteColor = Color.HSVToColor(floatArrayOf(idx * 30f, 0.55f, 1f))

        // Core glow in the note's own hue: the circle "reacts" to the note.
        corePaint.color = noteColor
        corePaint.alpha = (noteAlpha * 55f).toInt()
        canvas.drawCircle(cx, cy, r0 * (0.62f + pulse * 0.3f), corePaint)

        notePaint.color = noteColor
        notePaint.alpha = a
        notePaint.textSize = r0 * 0.40f
        canvas.drawText("$name$octave", cx, cy + notePaint.textSize * 0.35f, notePaint)

        // Cents bar: -50 .. +50, marker turns white when within +-10 cents.
        val half = r0 * 0.55f
        val y = cy + r0 * 0.55f
        tunerPaint.color = Color.argb(a / 3, 255, 255, 255)
        tunerPaint.strokeWidth = 4f
        canvas.drawLine(cx - half, y, cx + half, y, tunerPaint)
        canvas.drawLine(cx, y - 10f, cx, y + 10f, tunerPaint)
        val mx = cx + half * (cents / 50f)
        tunerPaint.color = if (abs(cents) <= 10) Color.argb(a, 255, 255, 255) else noteColor
        tunerPaint.alpha = a
        canvas.drawCircle(mx, y, 9f, tunerPaint)
    }

    private fun sampleBand(spectrum: FloatArray, bar: Int): Float {
        if (spectrum.isEmpty()) return 0f
        // mirror so the ring is symmetric: 0..half maps low->high, half..end back
        val half = BARS / 2
        val pos = if (bar < half) bar.toFloat() / half else (BARS - bar).toFloat() / half
        val f = pos * (AudioEngine.BANDS - 1)
        val i0 = f.toInt().coerceIn(0, AudioEngine.BANDS - 1)
        val i1 = (i0 + 1).coerceAtMost(AudioEngine.BANDS - 1)
        val t = f - i0
        return spectrum[i0] * (1 - t) + spectrum[i1] * t
    }

    companion object {
        private const val BARS = 96
    }
}
