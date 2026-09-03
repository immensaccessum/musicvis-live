package com.musicvis.live.wallpapers

import android.app.WallpaperColors
import android.graphics.Rect
import android.opengl.GLES20
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.musicvis.live.BackgroundPainter
import com.musicvis.live.BeatHaptics
import com.musicvis.live.FeaturePrefs
import com.musicvis.live.HistogramColors
import com.musicvis.live.PartyFx
import com.musicvis.live.audio.AudioEngine
import kotlin.math.max
import com.musicvis.live.gl.BarGlRenderer
import com.musicvis.live.gl.EglWindow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

abstract class GlBarWallpaperService : WallpaperService() {
    enum class Mode { PCM, FFT, OCTAVE }

    abstract val mode: Mode
    override fun onCreateEngine(): Engine = GlEngine()
    open fun engineColors(): WallpaperColors? = HistogramColors.wallpaperColors(this)

    inner class GlEngine : Engine(), Choreographer.FrameCallback {
        private val choreographer = Choreographer.getInstance()
        private val egl = EglWindow()
        private var gl = BarGlRenderer()
        private var texKey: String? = null
        private var bgColors: IntArray? = null
        private var bgLevel = 0f
        private val partyFx = PartyFx(this@GlBarWallpaperService)
        private val trailHeights = FloatArray(BarGlRenderer.BARS)
        private lateinit var audio: AudioEngine
        private lateinit var haptics: BeatHaptics
        private var visible = false
        private var posted = false
        private var eglReady = false
        private val heights = FloatArray(BarGlRenderer.BARS)
        private val analyzer = FloatArray(256)
        private val logDecay = FloatArray(BarGlRenderer.BARS)
        @Volatile private var xOffset = 0.5f
        @Volatile private var tiltBoost = 0f
        private var w1 = 0; private var a1 = 0
        private var w2 = 0; private var a2 = 0
        private var w3 = 0; private var a3 = 0
        private var w4 = 0; private var a4 = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            audio = AudioEngine.get(this@GlBarWallpaperService)
            haptics = BeatHaptics(this@GlBarWallpaperService)
            setOffsetNotificationsEnabled(true)
            setTouchEventsEnabled(true)
        }

        override fun onComputeColors(): WallpaperColors? = engineColors()

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            this.xOffset = xOffset
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (event.action == MotionEvent.ACTION_DOWN) tiltBoost = 1f
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                audio.acquire()
                postFrame()
            } else {
                audio.release()
                posted = false
                choreographer.removeFrameCallback(this)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            connectEgl()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (eglReady && egl.makeCurrent()) {
                GLES20.glViewport(0, 0, width, height)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            posted = false
            choreographer.removeFrameCallback(this)
            eglReady = false
            egl.release()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            visible = false
            posted = false
            choreographer.removeFrameCallback(this)
            eglReady = false
            egl.release()
            super.onDestroy()
        }

        private fun connectEgl() {
            val surf = surfaceHolder.surface ?: return
            eglReady = egl.attach(surf)
            if (eglReady) {
                // Fresh EGL context: old program/texture names are stale.
                gl = BarGlRenderer()
                texKey = null
                gl.init()
                val frame: Rect = surfaceHolder.surfaceFrame
                GLES20.glViewport(0, 0, frame.width(), frame.height())
            }
        }

        private fun postFrame() {
            if (!posted && visible) {
                posted = true
                choreographer.postFrameCallback(this)
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            posted = false
            if (!visible) return
            tiltBoost *= 0.9f
            fillHeights()
            partyFx.tick(audio)
            haptics.tick(audio, externalBeat = partyFx.beat)
            if (FeaturePrefs.party(this@GlBarWallpaperService)) {
                for (i in heights.indices) heights[i] = (heights[i] * 1.35f).coerceAtMost(0.95f)
            }
            val trailsOn = FeaturePrefs.trailsOn(this@GlBarWallpaperService)
            if (trailsOn) {
                for (i in heights.indices) {
                    trailHeights[i] = max(heights[i], trailHeights[i] * 0.955f)
                }
            }
            if (eglReady && egl.makeCurrent()) {
                // Decode the gradient bitmap only when the palette changes:
                // doing it every frame at 120 Hz floods the heap and ends in OOM.
                val key = HistogramColors.textureKey(this@GlBarWallpaperService)
                if (key != texKey) {
                    texKey = key
                    gl.uploadTexture(HistogramColors.loadTexture(this@GlBarWallpaperService), key)
                    bgColors = null
                }
                val useBg = FeaturePrefs.bgGradient(this@GlBarWallpaperService)
                if (useBg && bgColors == null) {
                    val c = HistogramColors.loadEffective(this@GlBarWallpaperService)
                    bgColors = intArrayOf(
                        BackgroundPainter.dark(c[0]),
                        BackgroundPainter.dark(c[1]),
                        BackgroundPainter.dark(c[2])
                    )
                }
                bgLevel = bgLevel * 0.92f + audio.rms.coerceIn(0f, 1f) * 0.08f
                val rot = (xOffset - 0.5f) * 0.15f + tiltBoost * 0.05f
                gl.draw(
                    heights, rot, 0f,
                    if (useBg) bgColors else null, bgLevel,
                    if (trailsOn) trailHeights else null,
                    partyFx.flash
                )
                egl.swap()
            }
            postFrame()
        }

        private fun fillHeights() {
            val n = heights.size
            if (audio.audioIdle) {
                idleHeights()
                return
            }
            when (mode) {
                Mode.PCM -> {
                    val pcm = audio.pcm
                    for (i in 0 until n) {
                        heights[i] = (abs(pcm[i * pcm.size / n]) / 128f).coerceIn(0f, 1f) * 0.55f
                    }
                }
                Mode.FFT -> fftHeights()
                Mode.OCTAVE -> octaveHeights()
            }
        }

        private fun octaveHeights() {
            val raw = audio.fftRaw
            if (raw.size < 8) {
                idleHeights()
                return
            }
            val bins = raw.size / 2
            val n = heights.size
            val lmin = ln(2f)
            val lmax = ln((bins - 1).toFloat())
            for (i in 0 until n) {
                val b = exp(lmin + (lmax - lmin) * i / (n - 1)).toInt().coerceIn(2, bins - 1)
                val re = raw[b * 2].toInt()
                val im = raw[b * 2 + 1].toInt()
                val m = sqrt((re * re + im * im).toFloat()) / 170f
                val old = logDecay[i]
                val v = if (m > old) m else old * 0.90f
                logDecay[i] = v
                heights[i] = v.coerceIn(0f, 0.92f)
            }
        }

        private fun fftHeights() {
            val raw = audio.fftRaw
            if (raw.size < 8) {
                idleHeights()
                return
            }
            val half = raw.size / 2
            var len = (half / 2).coerceAtMost(analyzer.size)
            val vol = 1f
            for (i in 1 until len - 1) {
                val re = raw[i * 2].toInt()
                val im = raw[i * 2 + 1].toInt()
                var neu = (re * re + im * im) * (i / 16 + 1)
                val old = analyzer[i]
                if (neu < old - 800) neu = (old - 800).toInt()
                analyzer[i] = neu.toFloat()
            }
            val n = heights.size
            var src = 1
            var cnt = 0
            for (i in 0 until n) {
                var v = analyzer[src.coerceAtMost(len - 1)] / 8000f * vol
                heights[i] = v.coerceIn(0f, 0.92f)
                cnt += len
                if (cnt > n) {
                    src++
                    cnt -= n
                }
            }
        }

        private fun idleHeights() {
            val n = heights.size
            val amp1 = sin(0.007f * a1) * 0.45f
            val amp2 = sin(0.023f * a2) * 0.28f
            val amp3 = sin(0.011f * a3) * 0.12f
            for (i in 0 until n) {
                val mag = abs(
                    sin(0.013f * (w1 + i)) * amp1 + sin(0.029f * (w2 + i)) * amp2
                ) + abs(sin(0.017f * (w4 + i)) * amp3)
                heights[i] = mag.coerceIn(0f, 0.7f)
            }
            w1++; a1++; w2--; a2++; w3++; a3++; w4++; a4++
        }
    }
}
