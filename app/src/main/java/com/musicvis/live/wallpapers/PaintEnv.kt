package com.musicvis.live.wallpapers

import com.musicvis.live.BackgroundPainter
import com.musicvis.live.audio.AudioEngine

data class PaintEnv(
    val audio: AudioEngine,
    val bg: BackgroundPainter,
    val timeMs: Long,
    val xOffset: Float,
    val yOffset: Float,
    val xPixelOffset: Int,
    val tiltX: Float,
    val tiltY: Float,
    val dim: Float,
    val preview: Boolean,
    val cutoutTop: Float,
    val cornerRadius: Float,
    val touchX: Float,
    val touchY: Float,
    val touchBoost: Float,
    val trackLine: String?,
    val zoomOut: Boolean
)
