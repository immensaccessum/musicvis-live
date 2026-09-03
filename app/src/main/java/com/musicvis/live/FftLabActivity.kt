package com.musicvis.live

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.musicvis.live.audio.AudioEngine
import com.musicvis.live.gl.BarGlRenderer

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sin

class FftLabActivity : AppCompatActivity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var audio: AudioEngine
    private lateinit var haptics: BeatHaptics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fft_lab)
        audio = AudioEngine.get(this)
        haptics = BeatHaptics(this)

        val hapticSwitch = findViewById<MaterialSwitch>(R.id.haptic)
        hapticSwitch.isChecked = FeaturePrefs.haptics(this)
        hapticSwitch.setOnCheckedChangeListener { _, v -> FeaturePrefs.setHaptics(this, v) }

        val presetGroup = findViewById<RadioGroup>(R.id.presetGroup)
        val presetId = when (FeaturePrefs.hapticPreset(this)) {
            "bass" -> R.id.presetBass
            "rms" -> R.id.presetRms
            else -> R.id.presetKick
        }
        presetGroup.check(presetId)
        presetGroup.setOnCheckedChangeListener { _, id ->
            val preset = when (id) {
                R.id.presetBass -> "bass"
                R.id.presetRms -> "rms"
                else -> "kick"
            }
            FeaturePrefs.setHapticPreset(this, preset)
        }

        bindSlider(
            R.id.hapticSens, R.id.hapticSensLabel, R.string.haptic_sens,
            FeaturePrefs.hapticSensitivity(this)
        ) { FeaturePrefs.setHapticSensitivity(this, it) }
        bindSlider(
            R.id.hapticGap, R.id.hapticGapLabel, R.string.haptic_gap,
            FeaturePrefs.hapticMinGap(this)
        ) { FeaturePrefs.setHapticMinGap(this, it) }
        bindSlider(
            R.id.hapticStrength, R.id.hapticStrengthLabel, R.string.haptic_strength,
            FeaturePrefs.hapticStrength(this), pulseOnRelease = true
        ) { FeaturePrefs.setHapticStrength(this, it) }

        findViewById<Button>(R.id.pulse).setOnClickListener { haptics.pulse() }

        glView = findViewById(R.id.gl)
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(LabRenderer())
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun bindSlider(
        sliderId: Int,
        labelId: Int,
        labelRes: Int,
        initial: Int,
        pulseOnRelease: Boolean = false,
        save: (Int) -> Unit
    ) {
        val label = findViewById<TextView>(labelId)
        val slider = findViewById<Slider>(sliderId)
        slider.value = initial.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        label.text = getString(labelRes, initial)
        slider.addOnChangeListener { _, value, _ ->
            val n = value.toInt()
            save(n)
            label.text = getString(labelRes, n)
        }
        if (pulseOnRelease) {
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    haptics.pulse()
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        audio.acquire()
        glView.onResume()
    }

    override fun onPause() {
        glView.onPause()
        audio.release()
        super.onPause()
    }

    private inner class LabRenderer : GLSurfaceView.Renderer {
        private var gl = BarGlRenderer()
        private var texKey: String? = null
        private var bgColors: IntArray? = null
        private var bgLevel = 0f
        private val heights = FloatArray(BarGlRenderer.BARS)
        private val analyzer = FloatArray(256)
        private var w1 = 0; private var a1 = 0
        private var w2 = 0; private var a2 = 0

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            // New GL context: old names are invalid, start clean.
            gl = BarGlRenderer()
            texKey = null
            gl.init()
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            android.opengl.GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(unused: GL10?) {
            fill()
            haptics.tick(audio)
            val key = HistogramColors.textureKey(this@FftLabActivity)
            if (key != texKey) {
                texKey = key
                gl.uploadTexture(HistogramColors.loadTexture(this@FftLabActivity), key)
                bgColors = null
            }
            val useBg = FeaturePrefs.bgGradient(this@FftLabActivity)
            if (useBg && bgColors == null) {
                val c = HistogramColors.loadEffective(this@FftLabActivity)
                bgColors = intArrayOf(
                    BackgroundPainter.dark(c[0]),
                    BackgroundPainter.dark(c[1]),
                    BackgroundPainter.dark(c[2])
                )
            }
            bgLevel = bgLevel * 0.92f + audio.rms.coerceIn(0f, 1f) * 0.08f
            gl.draw(heights, 0f, 0f, if (useBg) bgColors else null, bgLevel)
        }

        private fun fill() {
            val n = heights.size
            val raw = audio.fftRaw
            if (audio.audioIdle || raw.size < 8) {
                val amp1 = sin(0.007f * a1) * 0.45f
                val amp2 = sin(0.023f * a2) * 0.28f
                for (i in 0 until n) {
                    heights[i] = abs(sin(0.013f * (w1 + i)) * amp1 + sin(0.029f * (w2 + i)) * amp2)
                        .coerceIn(0f, 0.7f)
                }
                w1++; a1++; w2--; a2++
                return
            }
            val len = (raw.size / 4).coerceAtMost(analyzer.size)
            for (i in 1 until len - 1) {
                val re = raw[i * 2].toInt()
                val im = raw[i * 2 + 1].toInt()
                var neu = (re * re + im * im) * (i / 16 + 1)
                val old = analyzer[i]
                if (neu < old - 800) neu = (old - 800).toInt()
                analyzer[i] = neu.toFloat()
            }
            var src = 1
            var cnt = 0
            for (i in 0 until n) {
                heights[i] = (analyzer[src.coerceAtMost(len - 1)] / 8000f).coerceIn(0f, 0.92f)
                cnt += len
                if (cnt > n) {
                    src++
                    cnt -= n
                }
            }
        }
    }
}
