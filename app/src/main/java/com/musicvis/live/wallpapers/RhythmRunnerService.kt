package com.musicvis.live.wallpapers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.musicvis.live.PaletteCache
import kotlin.math.sin

/**
 * Geometry-Dash style auto-runner built from the music itself.
 * Foreground: a blocky tiled track (bass = height in whole blocks), spikes
 * planted on beats, a cube that jumps them right on the rhythm.
 * Background: a distant "concert" — an equalizer skyline, swaying spotlight
 * beams and a strobe that flares on every beat.
 */
class RhythmRunnerService : VisWallpaperService() {
    private val pal = PaletteCache(64)
    private val groundFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.MITER
    }
    private val gridPaint = Paint().apply { strokeWidth = 2f }
    private val spikePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cubePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundPath = Path()
    private val fillPath = Path()
    private val spikePath = Path()
    private val beamPath = Path()

    // Terrain: block levels (whole blocks, 0..3), one column = one block.
    private val terrain = ArrayDeque<Int>()
    private var scroll = 0f
    private var smoothBass = 0f
    private var spd = 0f

    // Spikes: screen x positions moving left.
    private val spikes = ArrayDeque<Float>()

    // Cube physics: absolute bottom Y, vy is up-positive.
    private var cubeBottom = 0f
    private var vy = 0f
    private var airborne = false
    private var rotating = false
    private var rot = 0f
    private var idleTimer = 0f
    private var lastMs = 0L
    private var strobe = 0f
    private val skyline = FloatArray(SKY_BARS)

    // Beat tempo estimate: spikes are planted a whole number of beats away
    // from the cube, so the jump apex lands right on a beat.
    private var lastBeatMs = 0L
    private var beatPeriod = 0.5f

    // Terrain generator state: ±1 block steps with a minimum plateau length.
    private var lastLevel = 0
    private var run = 0

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
        val rms = audio.rms.coerceIn(0f, 1f)

        // ---- Track state ----
        val bassTarget = if (idle) 0.30f + 0.20f * sin(env.timeMs * 0.0012f) else audio.bass
        smoothBass = if (bassTarget > smoothBass) bassTarget else smoothBass * 0.96f

        // Heavily smoothed speed: the jump arc is precomputed, so the track
        // must not change pace mid-flight or the cube lands on a spike.
        val speedTarget = w * (if (idle) 0.30f else 0.32f + 0.22f * rms)
        spd = if (spd == 0f) speedTarget else spd * 0.985f + speedTarget * 0.015f
        val speed = spd
        val block = w / 22f
        // Buffer ~2.7 screens: spikes are planted on future columns too.
        while (terrain.size < 60) terrain.addLast(level())
        scroll += speed * dt
        while (scroll >= block) {
            terrain.removeFirst()
            terrain.addLast(level())
            scroll -= block
        }

        val base = h * 0.74f
        fun colOf(x: Float): Int = ((x + scroll) / block).toInt().coerceIn(0, terrain.size - 1)
        fun groundY(x: Float): Float = base - terrain[colOf(x)] * block

        if (env.beat) strobe = 1f

        // ---- Far plane: the concert ----
        drawStage(canvas, env, w, h, palette, rms, idle)

        // ---- Spikes: a whole number of beats away, snapped to the tile grid.
        // Track and spikes scroll at the same speed, so a spike planted on a
        // column stays on that column — the grid never drifts.
        val spikeW = block
        val jumpT = 0.6f
        val cubeX = w * 0.28f
        if (env.beat) {
            if (lastBeatMs != 0L) {
                val iv = (env.timeMs - lastBeatMs) / 1000f
                if (iv in 0.25f..1.5f) beatPeriod = beatPeriod * 0.7f + iv * 0.3f
            }
            lastBeatMs = env.timeMs
            if (!idle) {
                val beatDist = (beatPeriod * speed).coerceAtLeast(1f)
                val n = kotlin.math.ceil(((w + spikeW * 0.5f) - cubeX) / beatDist)
                val k = (((cubeX + n * beatDist) + scroll) / block).toInt()
                // The spike needs a flat pocket: two level tiles on each side,
                // so neither the spike nor the landing zone touches a step.
                var col = -1
                for (off in intArrayOf(0, 1, -1, 2, -2, 3)) {
                    val c = k + off
                    if (c in 2 until terrain.size - 2 &&
                        terrain[c] == terrain[c - 2] && terrain[c] == terrain[c - 1] &&
                        terrain[c] == terrain[c + 1] && terrain[c] == terrain[c + 2]
                    ) {
                        col = c
                        break
                    }
                }
                if (col > 0) {
                    val sx = (col + 0.5f) * block - scroll
                    if ((spikes.isEmpty() || sx - spikes.last() >= speed * jumpT * 1.35f) &&
                        sx - cubeX > speed * jumpT
                    ) {
                        spikes.addLast(sx)
                    }
                }
            }
        }
        for (i in spikes.indices) spikes[i] -= speed * dt
        while (spikes.isNotEmpty() && spikes.first() < -spikeW * 2) spikes.removeFirst()

        // ---- Cube: real physics — jumps walls, falls off ledges ----
        val cube = block * 1.15f
        val jumpH = h * 0.16f
        val grav = 8f * jumpH / (jumpT * jumpT)
        val groundNow = groundY(cubeX)
        if (cubeBottom == 0f) cubeBottom = groundNow
        if (!airborne) {
            if (cubeBottom < groundNow - 1f) {
                // The ground fell away: drop off the ledge.
                airborne = true
                rotating = false
                vy = 0f
            } else {
                cubeBottom = groundNow
                val trigger = speed * jumpT / 2f
                var jump = false
                for (s in spikes) {
                    val d = s - cubeX
                    if (d > 0f && d <= trigger) {
                        jump = true
                        break
                    }
                }
                if (!jump) {
                    // A wall ahead must be jumped, not ridden.
                    val cur = terrain[colOf(cubeX)]
                    var c = colOf(cubeX) + 1
                    while (c < terrain.size) {
                        val dx = c * block - scroll - cubeX
                        if (dx > trigger) break
                        if (terrain[c] > cur) {
                            if (dx > 0f) jump = true
                            break
                        }
                        c++
                    }
                }
                if (idle && !jump) {
                    idleTimer += dt
                    if (idleTimer > 1.6f) {
                        idleTimer = 0f
                        jump = true
                    }
                }
                if (jump) {
                    airborne = true
                    rotating = true
                    vy = grav * jumpT / 2f
                }
            }
        }
        if (airborne) {
            cubeBottom -= vy * dt
            vy -= grav * dt
            if (rotating) rot += 180f / jumpT * dt
            val floor = groundY(cubeX)
            if (vy < 0f && cubeBottom >= floor) {
                cubeBottom = floor
                vy = 0f
                rot = 0f
                airborne = false
                rotating = false
            }
        }

        // ---- Ground: blocky steps, GD style ----
        groundFill.color = dim(palette[56], 0.30f)
        groundLine.color = palette[44]
        gridPaint.color = Color.argb(34, 255, 255, 255)
        groundPath.reset()
        var col = 0
        var x = -scroll
        groundPath.moveTo(x, base - terrain[0] * block)
        while (col < terrain.size) {
            val y = base - terrain[col] * block
            groundPath.lineTo(x, y)
            groundPath.lineTo(x + block, y)
            x += block
            col++
        }
        fillPath.set(groundPath)
        fillPath.lineTo(x, h)
        fillPath.lineTo(-scroll, h)
        fillPath.close()
        canvas.drawPath(fillPath, groundFill)
        // Tile grid inside the fill.
        var gx = -scroll
        while (gx < w + block) {
            canvas.drawLine(gx, groundY(gx + 1f), gx, h, gridPaint)
            gx += block
        }
        var gy = base
        while (gy < h) {
            canvas.drawLine(0f, gy, w, gy, gridPaint)
            gy += block
        }
        canvas.drawPath(groundPath, groundLine)

        // ---- Spikes: exactly one tile, same fill and outline as the track ----
        spikePaint.color = dim(palette[56], 0.42f)
        for (s in spikes) {
            val sy = groundY(s)
            spikePath.reset()
            spikePath.moveTo(s - block / 2f, sy)
            spikePath.lineTo(s, sy - block)
            spikePath.lineTo(s + block / 2f, sy)
            spikePath.close()
            canvas.drawPath(spikePath, spikePaint)
            canvas.drawPath(spikePath, groundLine)
        }

        // ---- Cube drawing ----
        val cy = cubeBottom - cube / 2f
        cubePaint.color = palette[30]
        eyePaint.color = dim(palette[56], 0.25f)
        canvas.save()
        canvas.rotate(rot, cubeX, cy)
        canvas.drawRoundRect(
            cubeX - cube / 2f, cy - cube / 2f, cubeX + cube / 2f, cy + cube / 2f,
            cube * 0.16f, cube * 0.16f, cubePaint
        )
        val e = cube * 0.13f
        canvas.drawCircle(cubeX - cube * 0.16f, cy - cube * 0.10f, e, eyePaint)
        canvas.drawCircle(cubeX + cube * 0.16f, cy - cube * 0.10f, e, eyePaint)
        canvas.restore()

        strobe *= 0.88f
        if (strobe < 0.02f) strobe = 0f
    }

    /** Distant stage: equalizer skyline, spotlight beams, beat strobe. */
    private fun drawStage(
        canvas: Canvas, env: PaintEnv, w: Float, h: Float,
        palette: IntArray, rms: Float, idle: Boolean
    ) {
        val horizon = h * 0.56f

        // Beat strobe behind everything: a soft flare over the stage.
        if (strobe > 0f) {
            stagePaint.color = palette[20]
            stagePaint.alpha = (strobe * 70f).toInt()
            canvas.drawCircle(w * 0.62f, horizon - h * 0.10f, h * 0.16f + strobe * h * 0.06f, stagePaint)
        }

        // Spotlight beams swaying from above the stage.
        val t = env.timeMs * 0.001f
        beamPaint.color = palette[16]
        for (k in 0 until 3) {
            val ox = w * (0.30f + 0.22f * k)
            val sway = sin(t * (0.5f + 0.13f * k) + k * 2.1f) * w * 0.10f
            beamPaint.alpha = (14f + rms * 36f + strobe * 40f).toInt().coerceAtMost(90)
            beamPath.reset()
            beamPath.moveTo(ox, -h * 0.02f)
            beamPath.lineTo(ox + sway - w * 0.045f, horizon)
            beamPath.lineTo(ox + sway + w * 0.045f, horizon)
            beamPath.close()
            canvas.drawPath(beamPath, beamPaint)
        }

        // Equalizer skyline: the "crowd/stage" dancing at the horizon.
        val s = env.audio.spectrum
        val bw = w / SKY_BARS
        stagePaint.color = dim(palette[38], 0.55f)
        stagePaint.alpha = 150
        for (i in 0 until SKY_BARS) {
            val target = if (idle || s.isEmpty()) {
                0.15f + 0.10f * sin(i * 0.7f + t * 1.8f)
            } else {
                s[(i * s.size) / SKY_BARS].coerceIn(0f, 1f)
            }
            val old = skyline[i]
            skyline[i] = if (target > old) target else old * 0.92f
            val bh = skyline[i] * h * 0.10f
            canvas.drawRect(i * bw + 1f, horizon - bh, (i + 1) * bw - 1f, horizon, stagePaint)
        }
        // Thin stage edge.
        stagePaint.alpha = 70
        canvas.drawRect(0f, horizon, w, horizon + 3f, stagePaint)
    }

    /** Next terrain column: ±1 block steps only, plateaus of 4+ columns. */
    private fun level(): Int {
        val want = (smoothBass * 3.4f).toInt().coerceIn(0, 3)
        if (run < 4) {
            run++
            return lastLevel
        }
        val step = (want - lastLevel).coerceIn(-1, 1)
        if (step != 0) {
            lastLevel += step
            run = 1
        }
        return lastLevel
    }

    private fun dim(c: Int, k: Float): Int = Color.rgb(
        (Color.red(c) * k).toInt(),
        (Color.green(c) * k).toInt(),
        (Color.blue(c) * k).toInt()
    )

    companion object {
        private const val SKY_BARS = 28
    }
}
