package com.musicvis.live.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.musicvis.live.FeaturePrefs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two sources, same output format:
 * - System (default): Visualizer on the output mix (session 0), same path as
 *   AOSP MusicVis. The microphone stays off.
 * - Microphone: AudioRecord + own FFT, packed into the same byte layout as
 *   Visualizer, so every consumer works unchanged. Volume scaling is disabled
 *   (mic level does not depend on media volume).
 */
class AudioEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    @Volatile var pcm = IntArray(1024)
        private set
    @Volatile var audioIdle = true
        private set
    @Volatile var waveform = FloatArray(WAVE_POINTS) { 0.5f }
        private set
    @Volatile var spectrum = FloatArray(BANDS)
        private set
    @Volatile var rms = 0f
        private set
    @Volatile var rmsRaw = 0f
        private set
    @Volatile var bass = 0f
        private set
    @Volatile var kick = 0f
        private set
    @Volatile var sampleRate = 44100
        private set
    @Volatile var fftRaw = ByteArray(0)
        private set
    @Volatile var peak = false
        private set

    /** Detected voice pitch in Hz (mic mode only), 0 when none. */
    @Volatile var pitchHz = 0f
        private set

    /** Pitch clarity 0..1: how periodic the signal is (mic mode only). */
    @Volatile var pitchConf = 0f
        private set

    private var visualizer: Visualizer? = null
    @Volatile private var running = false
    @Volatile private var micMode = false
    private var micThread: Thread? = null
    @Volatile private var micRunning = false
    private var users = 0
    private var attachedSession = Int.MIN_VALUE
    private var peakHoldUntil = 0L
    private var lastSignalMs = 0L

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (running) attachBest(configs)
        }
    }

    @Synchronized
    fun acquire() {
        users++
        if (users == 1) start()
    }

    @Synchronized
    fun release() {
        users = max(0, users - 1)
        if (users == 0) stop()
    }

    private fun start() {
        running = true
        lastSignalMs = System.currentTimeMillis()
        audioIdle = false
        startCapture()
    }

    private fun stop() {
        running = false
        stopCapture()
        waveform = FloatArray(WAVE_POINTS) { 0.5f }
        pcm = IntArray(1024)
        audioIdle = true
        fftRaw = ByteArray(0)
        rms = 0f
        rmsRaw = 0f
        bass = 0f
        kick = 0f
        peak = false
        pitchHz = 0f
        pitchConf = 0f
    }

    private fun startCapture() {
        micMode = FeaturePrefs.micSource(appContext)
        if (!micMode) {
            pitchHz = 0f
            pitchConf = 0f
        }
        if (micMode) {
            startMic()
        } else {
            try {
                audioManager.registerAudioPlaybackCallback(playbackCallback, main)
            } catch (t: Throwable) {
                Log.w(TAG, "register playback callback", t)
            }
            attachBest(audioManager.activePlaybackConfigurations)
        }
    }

    private fun stopCapture() {
        try {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (_: Throwable) {
        }
        releaseVisualizer()
        attachedSession = Int.MIN_VALUE
        stopMic()
    }

    /** Call after the mic/system preference changes to swap the source live. */
    @Synchronized
    fun onSourcePrefChanged() {
        if (!running) return
        stopCapture()
        lastSignalMs = System.currentTimeMillis()
        startCapture()
    }

    private fun attachBest(configs: List<AudioPlaybackConfiguration>) {
        val sessions = LinkedHashSet<Int>()
        // Original MusicVis used session 0 (global output mix) first.
        sessions.add(0)
        for (c in configs) {
            val sid = sessionIdOf(c)
            if (sid != null && sid != 0) sessions.add(sid)
        }
        if (attachedSession in sessions && visualizer != null) return
        for (id in sessions) {
            if (tryVisualizer(id)) return
        }
    }

    private fun sessionIdOf(config: AudioPlaybackConfiguration): Int? {
        return try {
            val m = AudioPlaybackConfiguration::class.java.getMethod("getAudioSessionId")
            (m.invoke(config) as? Int)?.takeIf { it >= 0 }
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryVisualizer(session: Int): Boolean {
        releaseVisualizer()
        return try {
            val vis = Visualizer(session)
            val range = Visualizer.getCaptureSizeRange()
            val size = range[1].coerceAtMost(1024).coerceAtLeast(range[0])
            vis.captureSize = size
            vis.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            vis.measurementMode = Visualizer.MEASUREMENT_MODE_NONE
            vis.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer,
                        bytes: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (bytes != null) consumeWave(bytes)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer,
                        bytes: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (bytes != null) consumeFft(bytes, samplingRate)
                    }
                },
                Visualizer.getMaxCaptureRate(),
                true,
                true
            )
            vis.enabled = true
            visualizer = vis
            attachedSession = session
            Log.i(TAG, "Visualizer attached session=$session size=$size")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Visualizer failed session=$session: ${t.message}")
            false
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Throwable) {
        }
        visualizer = null
    }

    private fun consumeWave(bytes: ByteArray) {
        val n = bytes.size
        if (n == 0) return
        val wave = FloatArray(WAVE_POINTS)
        val out = IntArray(1024)
        var silent = true
        var sum = 0.0
        val vol = mediaVolume()
        for (i in 0 until 1024) {
            val b = bytes[(i * n) / 1024]
            if (b != 0x80.toByte()) silent = false
            val tmp = ((b.toInt() and 0xFF) - 128)
            out[i] = (tmp * vol).toInt()
        }
        pcm = out
        val now = System.currentTimeMillis()
        if (!silent) lastSignalMs = now
        audioIdle = silent && (now - lastSignalMs > 3000)

        for (i in 0 until WAVE_POINTS) {
            val v = out[(i * 1024) / WAVE_POINTS] / 128f
            wave[i] = v * 0.5f + 0.5f
            sum += v * v
        }
        waveform = wave
        rmsRaw = sqrt(sum / WAVE_POINTS).toFloat()
        rms = rms * 0.65f + rmsRaw * 0.35f
        updatePeak(rmsRaw)
    }

    private fun consumeFft(bytes: ByteArray, samplingRate: Int) {
        if (samplingRate > 0) sampleRate = samplingRate
        val n = bytes.size
        if (n < 4) return
        fftRaw = bytes.copyOf()
        val bands = FloatArray(BANDS)
        val bins = n / 2
        for (b in 0 until BANDS) {
            val i0 = 1 + b * (bins - 1) / BANDS
            val i1 = 1 + (b + 1) * (bins - 1) / BANDS
            var m = 0f
            var i = i0
            while (i < i1 && i * 2 + 1 < n) {
                val re = bytes[i * 2].toInt()
                val im = bytes[i * 2 + 1].toInt()
                m += hypot(re.toFloat(), im.toFloat())
                i++
            }
            bands[b] = ((ln(1.0 + m / 24.0) / 4.0).toFloat() * mediaVolume()).coerceIn(0f, 1f)
        }
        spectrum = bands
        var low = 0f
        val cap = minOf(8, bins)
        for (i in 1 until cap) {
            if (i * 2 + 1 >= n) break
            val re = bytes[i * 2].toInt()
            val im = bytes[i * 2 + 1].toInt()
            low += hypot(re.toFloat(), im.toFloat())
        }
        bass = (ln(1.0 + low / 12.0) / 3.5).toFloat().coerceIn(0f, 1f)
        var ke = 0f
        val kickBins = minOf(3, bins - 1)
        for (i in 1..kickBins) {
            if (i * 2 + 1 >= n) break
            val re = bytes[i * 2].toInt()
            val im = bytes[i * 2 + 1].toInt()
            ke += re * re + im * im
        }
        kick = (sqrt(ke) / 200f).coerceIn(0f, 1f)
    }

    private fun mediaVolume(): Float {
        // Mic input does not depend on the media volume.
        if (micMode) return 1f
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(0)
        val lin = (cur.toFloat() / max).coerceIn(0f, 1f)
        // Concave curve: quick rise at quiet listening levels, slow near max
        // (lin 0.2 -> 0.55, 0.4 -> 0.72, 0.7 -> 0.88, 1.0 -> 1.0).
        return lin.toDouble().pow(0.37).toFloat()
    }

    private fun startMic() {
        micRunning = true
        micThread = Thread({ micLoop() }, "MicCapture").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopMic() {
        micRunning = false
        micThread?.join(300)
        micThread = null
    }

    private fun micLoop() {
        val rate = 44100
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, 8192)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord create failed", t)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized")
            rec.release()
            return
        }
        try {
            rec.startRecording()
            val n = 1024
            val buf = ShortArray(n)
            val waveBytes = ByteArray(n)
            val re = FloatArray(n)
            val im = FloatArray(n)
            val fftBytes = ByteArray(n)
            while (micRunning) {
                val read = rec.read(buf, 0, n)
                if (read <= 0) {
                    Thread.sleep(40)
                    continue
                }
                // 16-bit -> Visualizer-style unsigned 8-bit centered at 128.
                for (i in 0 until n) {
                    val s = buf[if (read >= n) i else i * read / n].toInt()
                    var v = (s * MIC_GAIN) shr 8
                    if (v > 127) v = 127
                    if (v < -128) v = -128
                    // Kill the noise floor so silence detection still works.
                    if (v in -2..2) v = 0
                    waveBytes[i] = (v + 128).toByte()
                }
                consumeWave(waveBytes)
                for (i in 0 until n) {
                    re[i] = ((waveBytes[i].toInt() and 0xFF) - 128).toFloat()
                    im[i] = 0f
                }
                fft(re, im)
                // Pack like Visualizer: {Re0, ReN/2, Re1, Im1, Re2, Im2, ...}
                fftBytes[0] = clamp8(re[0] * 2f / n)
                fftBytes[1] = clamp8(re[n / 2] * 2f / n)
                for (k in 1 until n / 2) {
                    fftBytes[k * 2] = clamp8(re[k] * 2f / n)
                    fftBytes[k * 2 + 1] = clamp8(im[k] * 2f / n)
                }
                consumeFft(fftBytes, rate)
                // Pitch from the raw 16-bit samples, every other block (~20/s).
                pitchTick++
                if (pitchTick and 1 == 0) detectPitch(buf, if (read >= n) n else read, rate)
            }
            rec.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "mic loop", t)
        } finally {
            rec.release()
        }
    }

    private fun clamp8(v: Float): Byte = v.toInt().coerceIn(-128, 127).toByte()

    private var pitchTick = 0

    /**
     * Normalized autocorrelation pitch detector for the humming range
     * (70–1000 Hz). Runs on the mic thread, so the render loop never pays.
     */
    private fun detectPitch(buf: ShortArray, n: Int, rate: Int) {
        var energy = 0.0
        for (i in 0 until n) {
            val s = buf[i].toDouble()
            energy += s * s
        }
        // Too quiet to be a deliberate hum.
        if (sqrt(energy / n) < 220.0) {
            pitchHz = 0f
            pitchConf = 0f
            return
        }
        val minLag = rate / 1000
        val maxLag = (rate / 70).coerceAtMost(n - 64)
        if (maxLag <= minLag) return
        val norms = FloatArray(maxLag + 1)
        var bestLag = -1
        var best = 0f
        for (lag in minLag..maxLag) {
            var ac = 0.0
            var e1 = 0.0
            var e2 = 0.0
            var i = 0
            while (i + lag < n) {
                val a = buf[i].toFloat()
                val b = buf[i + lag].toFloat()
                ac += a * b
                e1 += a * a
                e2 += b * b
                i++
            }
            val v = (ac / (sqrt(e1 * e2) + 1e-9)).toFloat()
            norms[lag] = v
            if (v > best) {
                best = v
                bestLag = lag
            }
        }
        if (bestLag < 0 || best < 0.5f) {
            pitchHz = 0f
            pitchConf = 0f
            return
        }
        // Octave fix: the true period is often the *smallest* lag whose peak is
        // nearly as strong as the global best.
        var lag = bestLag
        for (l in (minLag + 1) until bestLag) {
            if (norms[l] > best * 0.92f && norms[l] >= norms[l - 1] && norms[l] >= norms[l + 1]) {
                lag = l
                break
            }
        }
        // Parabolic refinement between neighbouring lags.
        val c0 = norms[(lag - 1).coerceAtLeast(minLag)]
        val c1 = norms[lag]
        val c2 = norms[(lag + 1).coerceAtMost(maxLag)]
        val denom = c0 - 2f * c1 + c2
        val delta = if (kotlin.math.abs(denom) > 1e-6f) {
            (0.5f * (c0 - c2) / denom).coerceIn(-0.5f, 0.5f)
        } else 0f
        pitchHz = rate / (lag + delta)
        pitchConf = c1.coerceIn(0f, 1f)
    }

    /** In-place iterative radix-2 FFT; array size must be a power of two. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun updatePeak(value: Float) {
        val now = System.currentTimeMillis()
        if (value > 0.55f) peakHoldUntil = now + 180
        peak = now < peakHoldUntil
    }

    companion object {
        private const val TAG = "MusicVisAudio"
        const val WAVE_POINTS = 128
        const val BANDS = 32
        // Extra gain for the 16-bit -> 8-bit conversion: mic signals are much
        // quieter than a normalized output mix.
        private const val MIC_GAIN = 4

        @Volatile private var instance: AudioEngine? = null

        fun get(context: Context): AudioEngine {
            return instance ?: synchronized(this) {
                instance ?: AudioEngine(context).also { instance = it }
            }
        }
    }
}
