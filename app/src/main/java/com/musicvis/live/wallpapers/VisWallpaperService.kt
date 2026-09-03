package com.musicvis.live.wallpapers

import android.app.WallpaperColors
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowInsets
import com.musicvis.live.BackgroundPainter
import com.musicvis.live.BeatHaptics
import com.musicvis.live.FeaturePrefs
import com.musicvis.live.PartyFx
import com.musicvis.live.HistogramColors
import com.musicvis.live.HistogramWidget
import com.musicvis.live.LiveIsland
import com.musicvis.live.NowPlaying
import com.musicvis.live.audio.AudioEngine

abstract class VisWallpaperService : WallpaperService() {
    abstract fun paint(canvas: Canvas, env: PaintEnv)
    open fun engineColors(): WallpaperColors? = HistogramColors.wallpaperColors(this)

    override fun onCreateEngine(): Engine = VisEngine()

    inner class VisEngine : Engine(), Choreographer.FrameCallback, SensorEventListener {
        private val choreographer = Choreographer.getInstance()
        private lateinit var audio: AudioEngine
        private var visible = false
        private var posted = false
        @Volatile private var xOffset = 0.5f
        @Volatile private var yOffset = 0f
        @Volatile private var xPixel = 0
        @Volatile private var tiltX = 0f
        @Volatile private var tiltY = 0f
        @Volatile private var zoom = 1f
        @Volatile private var cutoutTop = 0f
        @Volatile private var corner = 48f
        @Volatile private var touchX = 0.5f
        @Volatile private var touchY = 0.5f
        @Volatile private var touchBoost = 0f
        private var sensors: SensorManager? = null
        private val bgPainter = BackgroundPainter(this@VisWallpaperService)
        private val partyFx = PartyFx(this@VisWallpaperService)
        private val haptics = BeatHaptics(this@VisWallpaperService)
        private var lastWidget = 0L
        private var lastColorKey: String? = null
        private var frame = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            audio = AudioEngine.get(this@VisWallpaperService)
            setOffsetNotificationsEnabled(true)
            setTouchEventsEnabled(true)
            sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        }

        override fun onComputeColors(): WallpaperColors? = engineColors()

        override fun onZoomChanged(zoom: Float) {
            this.zoom = zoom
        }

        override fun onWallpaperFlagsChanged(which: Int) {}

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            this.xOffset = xOffset
            this.yOffset = yOffset
            this.xPixel = xPixelOffset
        }

        override fun onApplyWindowInsets(insets: WindowInsets) {
            if (Build.VERSION.SDK_INT >= 28) {
                val cut = insets.displayCutout
                cutoutTop = cut?.safeInsetTop?.toFloat() ?: insets.systemWindowInsetTop.toFloat()
            } else {
                cutoutTop = insets.systemWindowInsetTop.toFloat()
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val r = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
                if (r != null) corner = r.radius.toFloat()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (!FeaturePrefs.touchFx(this@VisWallpaperService)) return
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                val w = surfaceHolder.surfaceFrame.width().coerceAtLeast(1)
                val h = surfaceHolder.surfaceFrame.height().coerceAtLeast(1)
                touchX = event.x / w
                touchY = event.y / h
                touchBoost = 1f
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                audio.acquire()
                bindSensors()
                requestHz()
                notifyColorsChanged()
                LiveIsland.stop(this@VisWallpaperService)
                postFrame()
            } else {
                audio.release()
                unbindSensors()
                LiveIsland.stop(this@VisWallpaperService)
                posted = false
                choreographer.removeFrameCallback(this)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            requestHz()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            requestHz()
        }

        override fun onDestroy() {
            if (visible && ::audio.isInitialized) audio.release()
            visible = false
            posted = false
            unbindSensors()
            choreographer.removeFrameCallback(this)
            super.onDestroy()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            posted = false
            choreographer.removeFrameCallback(this)
            super.onSurfaceDestroyed(holder)
        }

        private fun requestHz() {
            try {
                val hz = if (Build.VERSION.SDK_INT >= 30) {
                    display?.mode?.refreshRate ?: 120f
                } else 60f
                if (Build.VERSION.SDK_INT >= 30) {
                    surfaceHolder.surface.setFrameRate(
                        hz.coerceIn(60f, 144f),
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                    )
                }
            } catch (_: Throwable) {
            }
        }

        private fun bindSensors() {
            if (!FeaturePrefs.tilt(this@VisWallpaperService)) return
            val sm = sensors ?: return
            val g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (g != null) sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME)
        }

        private fun unbindSensors() {
            try {
                sensors?.unregisterListener(this)
            } catch (_: Throwable) {
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            tiltX = (event.values[0] / 9.81f).coerceIn(-1f, 1f)
            tiltY = (event.values[1] / 9.81f).coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        private fun postFrame() {
            if (!posted && visible) {
                posted = true
                choreographer.postFrameCallback(this)
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            posted = false
            if (!visible) return
            touchBoost *= 0.92f
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        holder.lockHardwareCanvas()
                    } catch (_: Throwable) {
                        holder.lockCanvas()
                    }
                } else holder.lockCanvas()
                if (canvas != null) {
                    if (corner > 0f) {
                        canvas.save()
                        val rr = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
                        val path = android.graphics.Path()
                        path.addRoundRect(rr, corner, corner, android.graphics.Path.Direction.CW)
                        canvas.clipPath(path)
                    }
                    if (FeaturePrefs.zoomOut(this@VisWallpaperService) && zoom != 0f && zoom != 1f) {
                        canvas.save()
                        canvas.scale(1f + zoom * 0.12f, 1f + zoom * 0.12f, canvas.width / 2f, canvas.height / 2f)
                    }
                    val track = if (FeaturePrefs.nowPlaying(this@VisWallpaperService)) NowPlaying.line else null
                    partyFx.tick(audio)
                    haptics.tick(audio, externalBeat = partyFx.beat)
                    paint(
                        canvas,
                        PaintEnv(
                            audio = audio,
                            bg = bgPainter,
                            timeMs = SystemClock.uptimeMillis(),
                            xOffset = xOffset,
                            yOffset = yOffset,
                            xPixelOffset = xPixel,
                            tiltX = if (FeaturePrefs.tilt(this@VisWallpaperService)) tiltX else 0f,
                            tiltY = if (FeaturePrefs.tilt(this@VisWallpaperService)) tiltY else 0f,
                            dim = zoom,
                            preview = isPreview,
                            cutoutTop = cutoutTop,
                            cornerRadius = corner,
                            touchX = touchX,
                            touchY = touchY,
                            touchBoost = touchBoost,
                            trackLine = track,
                            zoomOut = FeaturePrefs.zoomOut(this@VisWallpaperService)
                        )
                    )
                    partyFx.draw(canvas)
                    if (FeaturePrefs.zoomOut(this@VisWallpaperService) && zoom != 0f && zoom != 1f) {
                        canvas.restore()
                    }
                    if (corner > 0f) canvas.restore()
                }
            } catch (_: Throwable) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Throwable) {
                    }
                }
            }
            frame++
            val now = SystemClock.uptimeMillis()
            if (now - lastWidget > 2000) {
                lastWidget = now
                HistogramWidget.push(this@VisWallpaperService, audio)
            }
            // Base key only: the auto color-cycle must not spam the system
            // with WallpaperColors updates several times per second.
            val key = HistogramColors.baseKey(this@VisWallpaperService)
            if (key != lastColorKey) {
                lastColorKey = key
                notifyColorsChanged()
            }
            postFrame()
        }
    }
}
