package com.musicvis.live

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.musicvis.live.audio.AudioEngine
import kotlin.math.max

/**
 * Beat-synced vibration. The trigger source is a user preset:
 * kick (40–130 Hz front), any bass, or overall loudness.
 * Sensitivity scales the onset gate; min gap is the refractory period.
 * For the default "kick" preset the caller may pass the PartyFx beat so the
 * pulse lands exactly when a shockwave ring is born.
 */
class BeatHaptics(context: Context) {
    private val app = context.applicationContext
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private var last = 0L
    private var prev = 0f
    private var fluxAvg = 0.03f

    fun pulse() = fire()

    fun tick(
        audio: AudioEngine,
        enabled: Boolean = FeaturePrefs.haptics(app),
        externalBeat: Boolean? = null
    ) {
        if (!enabled || audio.audioIdle) {
            prev *= 0.85f
            return
        }
        val preset = FeaturePrefs.hapticPreset(app)
        // Default preset follows the shared PartyFx detector: one beat = one ring = one pulse.
        if (externalBeat != null && preset == "kick") {
            if (!externalBeat) return
            val gap = FeaturePrefs.hapticMinGap(app).toLong()
            if (System.currentTimeMillis() - last < gap) return
            fire()
            return
        }
        val e = when (preset) {
            "bass" -> audio.bass
            "rms" -> audio.rmsRaw
            else -> audio.kick
        }
        val flux = (e - prev).coerceAtLeast(0f)
        prev = e
        fluxAvg = fluxAvg * 0.94f + flux * 0.06f

        // sens 0 -> strict (x3.4 above average), 100 -> loose (x0.6)
        val sens = FeaturePrefs.hapticSensitivity(app).coerceIn(0, 100)
        val mult = 3.4f - sens * 0.028f
        val floor = 0.06f - sens * 0.0004f
        if (flux < max(fluxAvg * mult, floor)) return

        val gap = FeaturePrefs.hapticMinGap(app).toLong()
        if (System.currentTimeMillis() - last < gap) return
        fire()
    }

    private fun fire() {
        last = System.currentTimeMillis()
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val s = FeaturePrefs.hapticStrength(app).coerceIn(0, 100)
        if (s <= 0) return
        try {
            vibrate(v, effectFor(v, s))
        } catch (_: Throwable) {
        }
    }

    private fun effectFor(v: Vibrator, s: Int): VibrationEffect {
        val ms = (14L + s / 8).coerceIn(14L, 28L)
        val amp = if (v.hasAmplitudeControl()) {
            (90 + s * 165 / 100).coerceIn(1, 255)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        return VibrationEffect.createOneShot(ms, amp)
    }

    private fun vibrate(v: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(
                effect,
                VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build()
            )
        } else {
            v.vibrate(effect)
        }
    }
}
