package com.musicvis.live

import android.content.Context
import androidx.core.content.edit

object FeaturePrefs {
    private const val P = "features"

    fun tilt(c: Context) = p(c).getBoolean("tilt", true)
    fun island(c: Context) = p(c).getBoolean("island", false)
    fun nowPlaying(c: Context) = p(c).getBoolean("nowplaying", true)
    fun touchFx(c: Context) = p(c).getBoolean("touch", true)
    fun zoomOut(c: Context) = p(c).getBoolean("zoom", true)
    fun haptics(c: Context) = p(c).getBoolean("haptics", false)
    fun hapticStrength(c: Context) = p(c).getInt("haptic_str", 80)
    fun hapticPreset(c: Context) = p(c).getString("haptic_preset", "kick") ?: "kick"
    fun hapticSensitivity(c: Context) = p(c).getInt("haptic_sens", 50)
    fun hapticMinGap(c: Context) = p(c).getInt("haptic_gap", 250)
    fun micSource(c: Context) = p(c).getBoolean("mic_source", false)
    fun noteDisplay(c: Context) = p(c).getBoolean("note_display", false)
    fun bgGradient(c: Context) = p(c).getBoolean("bg_gradient", false)
    fun fxFlash(c: Context) = p(c).getBoolean("fx_flash", false)
    fun fxColorCycle(c: Context) = p(c).getBoolean("fx_cycle", false)
    fun fxWaves(c: Context) = p(c).getBoolean("fx_waves", false)
    fun fxTrails(c: Context) = p(c).getBoolean("fx_trails", false)
    fun party(c: Context) = p(c).getBoolean("party", false)

    // Effective states: party mode turns everything on at once.
    fun flashOn(c: Context) = party(c) || fxFlash(c)
    fun colorCycleOn(c: Context) = party(c) || fxColorCycle(c)
    fun wavesOn(c: Context) = party(c) || fxWaves(c)
    fun trailsOn(c: Context) = party(c) || fxTrails(c)

    fun setTilt(c: Context, v: Boolean) = p(c).edit { putBoolean("tilt", v) }
    fun setIsland(c: Context, v: Boolean) = p(c).edit { putBoolean("island", v) }
    fun setNowPlaying(c: Context, v: Boolean) = p(c).edit { putBoolean("nowplaying", v) }
    fun setTouchFx(c: Context, v: Boolean) = p(c).edit { putBoolean("touch", v) }
    fun setZoomOut(c: Context, v: Boolean) = p(c).edit { putBoolean("zoom", v) }
    fun setHaptics(c: Context, v: Boolean) = p(c).edit { putBoolean("haptics", v) }
    fun setHapticStrength(c: Context, v: Int) = p(c).edit { putInt("haptic_str", v.coerceIn(0, 100)) }
    fun setHapticPreset(c: Context, v: String) = p(c).edit { putString("haptic_preset", v) }
    fun setHapticSensitivity(c: Context, v: Int) = p(c).edit { putInt("haptic_sens", v.coerceIn(0, 100)) }
    fun setHapticMinGap(c: Context, v: Int) = p(c).edit { putInt("haptic_gap", v.coerceIn(100, 800)) }
    fun setMicSource(c: Context, v: Boolean) = p(c).edit { putBoolean("mic_source", v) }
    fun setNoteDisplay(c: Context, v: Boolean) = p(c).edit { putBoolean("note_display", v) }
    fun setBgGradient(c: Context, v: Boolean) = p(c).edit { putBoolean("bg_gradient", v) }
    fun setFxFlash(c: Context, v: Boolean) = p(c).edit { putBoolean("fx_flash", v) }
    fun setFxColorCycle(c: Context, v: Boolean) = p(c).edit { putBoolean("fx_cycle", v) }
    fun setFxWaves(c: Context, v: Boolean) = p(c).edit { putBoolean("fx_waves", v) }
    fun setFxTrails(c: Context, v: Boolean) = p(c).edit { putBoolean("fx_trails", v) }
    fun setParty(c: Context, v: Boolean) = p(c).edit { putBoolean("party", v) }

    private fun p(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)
}
