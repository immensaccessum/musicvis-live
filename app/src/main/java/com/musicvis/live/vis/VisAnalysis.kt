package com.musicvis.live.vis

import com.musicvis.live.audio.AudioEngine
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class VisAnalysis {
    private val decay = FloatArray(256)
    private var w1 = 0; private var a1 = 0
    private var w2 = 0; private var a2 = 0
    private var w4 = 0; private var a4 = 0
    private val chromaAcc = FloatArray(12)

    fun fillBars(kind: VisKind, audio: AudioEngine, out: FloatArray) {
        if (audio.audioIdle) {
            idle(out)
            return
        }
        when (kind) {
            VisKind.WAVEFORM -> pcm(audio, out)
            VisKind.SPECTRUM -> fft(audio, out, log = false)
            VisKind.OCTAVE -> fft(audio, out, log = true)
            else -> fft(audio, out, log = false)
        }
    }

    fun chroma(audio: AudioEngine, out: FloatArray) {
        out.fill(0f)
        if (out.size < 12) return
        val raw = audio.fftRaw
        if (audio.audioIdle || raw.size < 8) {
            idle(out)
            return
        }
        chromaAcc.fill(0f)
        val bins = raw.size / 2
        val sr = audio.sampleRate.coerceAtLeast(8000)
        val hzPer = (sr / 2f) / bins.coerceAtLeast(1)
        for (i in 2 until bins) {
            if (i * 2 + 1 >= raw.size) break
            val hz = i * hzPer
            if (hz < 50f || hz > 5000f) continue
            val midi = 69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)
            if (!midi.isFinite()) continue
            val pc = ((midi.roundToIntSafe() % 12) + 12) % 12
            val re = raw[i * 2].toInt()
            val im = raw[i * 2 + 1].toInt()
            chromaAcc[pc] += hypot(re.toFloat(), im.toFloat())
        }
        var m = 0.001f
        for (v in chromaAcc) m = max(m, v)
        for (i in 0 until 12) out[i] = (chromaAcc[i] / m).coerceIn(0f, 1f)
    }

    private fun pcm(audio: AudioEngine, out: FloatArray) {
        val pcm = audio.pcm
        val n = out.size
        for (i in 0 until n) {
            out[i] = (abs(pcm[i * pcm.size / n]) / 128f).coerceIn(0f, 1f) * 0.55f
        }
    }

    private fun fft(audio: AudioEngine, out: FloatArray, log: Boolean) {
        val raw = audio.fftRaw
        if (raw.size < 8) {
            idle(out)
            return
        }
        val half = raw.size / 2
        val len = (half / 2).coerceAtMost(decay.size)
        for (i in 1 until len - 1) {
            val re = raw[i * 2].toInt()
            val im = raw[i * 2 + 1].toInt()
            var neu = (re * re + im * im) * (i / 16 + 1)
            val old = decay[i]
            if (neu < old - 800) neu = (old - 800).toInt()
            decay[i] = neu.toFloat()
        }
        val n = out.size
        if (!log) {
            var src = 1
            var cnt = 0
            for (i in 0 until n) {
                out[i] = (decay[src.coerceAtMost(len - 1)] / 8000f).coerceIn(0f, 0.92f)
                cnt += len
                if (cnt > n) {
                    src++
                    cnt -= n
                }
            }
            return
        }
        out.fill(0f)
        for (i in 1 until len) {
            val t = ln(1.0 + i) / ln(len.toDouble())
            val b = (t * (n - 1)).toInt().coerceIn(0, n - 1)
            out[b] = max(out[b], decay[i] / 8000f)
        }
        for (i in out.indices) out[i] = out[i].coerceIn(0f, 0.92f)
    }

    fun idle(out: FloatArray) {
        val n = out.size
        val amp1 = sin(0.007f * a1) * 0.45f
        val amp2 = sin(0.023f * a2) * 0.28f
        for (i in 0 until n) {
            out[i] = abs(sin(0.013f * (w1 + i)) * amp1 + sin(0.029f * (w2 + i)) * amp2)
                .coerceIn(0f, 0.7f)
        }
        w1++; a1++; w2--; a2++; w4++; a4++
    }

    private fun Double.roundToIntSafe(): Int {
        val v = this
        if (v.isNaN() || v.isInfinite()) return 0
        return v.toInt()
    }
}
