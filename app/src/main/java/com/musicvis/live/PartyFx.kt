package com.musicvis.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import com.musicvis.live.audio.AudioEngine
import kotlin.math.hypot

/**
 * Party overlay effects shared by all modes: a full-screen flash and expanding
 * shockwave rings triggered by the kick. Uses its own onset detector (same
 * flux idea as BeatHaptics) so it works even when haptics are off.
 */
class PartyFx(private val context: Context) {
    private var prevKick = 0f
    private var fluxAvg = 0f
    private var lastBeat = 0L

    /** 1 right on the beat, decays quickly. 0 when the flash effect is off. */
    var flash = 0f
        private set

    /** True only on the frame the detector fired; syncs haptics with the rings. */
    var beat = false
        private set

    // Ring ages in 0..1; negative slot = free.
    private val rings = FloatArray(5) { -1f }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private var tint = Color.WHITE
    private var flashColor = Color.WHITE
    private var palKey: String? = null

    /** Advance the beat detector; call once per frame. */
    fun tick(audio: AudioEngine) {
        val k = audio.kick
        val flux = (k - prevKick).coerceAtLeast(0f)
        prevKick = k
        fluxAvg = fluxAvg * 0.95f + flux * 0.05f
        val now = SystemClock.uptimeMillis()
        beat = !audio.audioIdle && flux > fluxAvg * 2.2f + 0.05f && now - lastBeat > 230
        if (beat) {
            lastBeat = now
            if (FeaturePrefs.flashOn(context)) flash = 1f
            if (FeaturePrefs.wavesOn(context)) spawnRing()
        }
        flash *= 0.86f
        if (flash < 0.02f) flash = 0f
    }

    /** Rings + flash on top of an already painted frame. */
    fun draw(canvas: Canvas) {
        refreshTint()
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val maxR = hypot(w, h) * 0.55f
        for (i in rings.indices) {
            val age = rings[i]
            if (age < 0f) continue
            val fade = 1f - age
            ringPaint.color = tint
            ringPaint.alpha = (fade * 190f).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = 4f + 30f * fade
            canvas.drawCircle(w / 2f, h / 2f, 60f + age * maxR, ringPaint)
            rings[i] = if (age + 0.028f >= 1f) -1f else age + 0.028f
        }
        if (flash > 0f) {
            canvas.drawColor(
                Color.argb((flash * 130f).toInt().coerceIn(0, 255),
                    Color.red(flashColor), Color.green(flashColor), Color.blue(flashColor))
            )
        }
    }

    private fun spawnRing() {
        for (i in rings.indices) {
            if (rings[i] < 0f) {
                rings[i] = 0f
                return
            }
        }
    }

    private fun refreshTint() {
        val key = HistogramColors.textureKey(context)
        if (key == palKey) return
        palKey = key
        val mid = HistogramColors.loadEffective(context)[1]
        tint = HistogramColors.lerpColor(mid, Color.WHITE, 0.35f)
        flashColor = HistogramColors.lerpColor(mid, Color.WHITE, 0.7f)
    }
}
