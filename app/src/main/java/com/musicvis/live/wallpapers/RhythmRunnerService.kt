package com.musicvis.live.wallpapers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.musicvis.live.PaletteCache
import kotlin.math.sin

/**
 * Geometry-Dash style auto-runner built from the music itself:
 * hills come from the bass, a spike grows on every beat, and the cube
 * jumps over them right on the rhythm. The level *is* the track.
 */
class RhythmRunnerService : VisWallpaperService() {
    private val pal = PaletteCache(64)
    private val groundFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val spikePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cubePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundPath = Path()
    private val spikePath = Path()

    // Terrain: ring of hill heights (0..1), one column = w/40 px.
    private val terrain = ArrayDeque<Float>()
    private var scroll = 0f
    private var smoothBass = 0f

    // Spikes: screen x positions moving left.
    private val spikes = ArrayDeque<Float>()

    // Cube physics: offset above the ground, up is positive.
    private var yOff = 0f
    private var vy = 0f
    private var jumping = false
    private var rot = 0f
    private var idleTimer = 0f
    private var lastMs = 0L

    override fun paint(canvas: Canvas, env: PaintEnv) {
        pal.refresh(this)
        val palette = pal.colors
        if (palette.isEmpty()) return
        val audio = env.audio
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        env.bg.draw(canvas, audio, Color.rgb(6, 8, 14))

        val dt = if (lastMs == 0L) 0f else (env.timeMs - lastMs).coerceIn(0L, 50L) / 1000f
        lastMs = env.timeMs
        val idle = audio.audioIdle

        // Hills follow the bass: fast attack, slow release.
        val bassTarget = if (idle) 0.25f + 0.15f * sin(env.timeMs * 0.0012f) else audio.bass
        smoothBass = if (bassTarget > smoothBass) bassTarget else smoothBass * 0.96f

        val speed = w * (if (idle) 0.28f else 0.30f + 0.25f * audio.rms.coerceIn(0f, 1f))
        val colW = w / 40f
        while (terrain.size < 44) terrain.addLast(smoothBass)
        scroll += speed * dt
        while (scroll >= colW) {
            terrain.removeFirst()
            terrain.addLast(smoothBass)
            scroll -= colW
        }

        val base = h * 0.70f
        val amp = h * 0.10f
        fun groundY(x: Float): Float {
            val f = ((x + scroll) / colW).coerceIn(0f, (terrain.size - 2).toFloat())
            val i = f.toInt()
            val t = f - i
            return base - (terrain[i] * (1 - t) + terrain[i + 1] * t) * amp
        }

        // A beat plants a spike at the right edge (with a minimum spacing).
        val spikeW = colW * 1.0f
        if (env.beat && !idle &&
            (spikes.isEmpty() || spikes.last() < w - speed * 0.35f)
        ) {
            spikes.addLast(w + spikeW)
        }
        for (i in spikes.indices) spikes[i] -= speed * dt
        while (spikes.isNotEmpty() && spikes.first() < -spikeW * 2) spikes.removeFirst()

        // Cube: jump so the apex lands right above the incoming spike.
        val cubeX = w * 0.28f
        val cube = colW * 1.35f
        val jumpT = 0.55f
        val jumpH = h * 0.13f
        val g = 8f * jumpH / (jumpT * jumpT)
        if (!jumping) {
            val trigger = speed * jumpT / 2f
            for (s in spikes) {
                val d = s - cubeX
                if (d > cube * 0.4f && d < trigger) {
                    jumping = true
                    vy = g * jumpT / 2f
                    break
                }
            }
            if (idle) {
                idleTimer += dt
                if (idleTimer > 1.6f) {
                    idleTimer = 0f
                    jumping = true
                    vy = g * jumpT / 2f
                }
            }
        }
        if (jumping) {
            yOff += vy * dt
            vy -= g * dt
            rot += 180f / jumpT * dt
            if (yOff <= 0f && vy < 0f) {
                yOff = 0f
                vy = 0f
                rot = 0f
                jumping = false
            }
        }

        // Ground: dark fill below, bright line on top.
        groundFill.color = dim(palette[56], 0.30f)
        groundLine.color = palette[44]
        groundPath.reset()
        groundPath.moveTo(0f, groundY(0f))
        var x = colW / 2f
        while (x < w + colW) {
            groundPath.lineTo(x, groundY(x))
            x += colW / 2f
        }
        // Fill a closed copy first, then stroke the open profile over it.
        spikePath.set(groundPath)
        spikePath.lineTo(w + colW, h)
        spikePath.lineTo(0f, h)
        spikePath.close()
        canvas.drawPath(spikePath, groundFill)
        canvas.drawPath(groundPath, groundLine)

        // Spikes sit on the ground.
        spikePaint.color = palette[10]
        for (s in spikes) {
            val gy = groundY(s)
            spikePath.reset()
            spikePath.moveTo(s - spikeW / 2f, gy)
            spikePath.lineTo(s, gy - spikeW * 1.25f)
            spikePath.lineTo(s + spikeW / 2f, gy)
            spikePath.close()
            canvas.drawPath(spikePath, spikePaint)
        }

        // Cube with a simple face, rotating in flight like the original.
        val cy = groundY(cubeX) - yOff - cube / 2f
        cubePaint.color = palette[30]
        eyePaint.color = dim(palette[56], 0.25f)
        canvas.save()
        canvas.rotate(rot, cubeX, cy)
        canvas.drawRoundRect(
            cubeX - cube / 2f, cy - cube / 2f, cubeX + cube / 2f, cy + cube / 2f,
            cube * 0.18f, cube * 0.18f, cubePaint
        )
        val e = cube * 0.13f
        canvas.drawCircle(cubeX - cube * 0.16f, cy - cube * 0.10f, e, eyePaint)
        canvas.drawCircle(cubeX + cube * 0.16f, cy - cube * 0.10f, e, eyePaint)
        canvas.restore()
    }

    private fun dim(c: Int, k: Float): Int = Color.rgb(
        (Color.red(c) * k).toInt(),
        (Color.green(c) * k).toInt(),
        (Color.blue(c) * k).toInt()
    )
}
