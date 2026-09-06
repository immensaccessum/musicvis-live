package com.musicvis.live.wallpapers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.musicvis.live.PaletteCache
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Geometry-Dash style auto-runner built from the music itself.
 *
 * The whole level is one "tape" of tile columns: the terrain level and the
 * spike flag live on the column, so spikes are always exactly one tile and
 * always on the grid — the tape scrolls as a whole and nothing can drift.
 * A hard invariant makes clipping impossible by construction: any two
 * obstacles (spike or terrain step) are at least [GAP] columns apart, which
 * is longer than a full jump.
 *
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

    // ---- Level tape ----
    private val levels = ArrayDeque<Int>()
    private val spikeAt = ArrayDeque<Boolean>()
    private var scroll = 0f
    private var genCol = 0          // absolute index of the next column to generate
    private var lastObstacle = -GAP // absolute column of the latest obstacle
    private var lastLevel = 0

    private var smoothBass = 0f
    private var spd = 0f

    // ---- Cube physics: absolute bottom Y, vy is up-positive ----
    private var cubeBottom = 0f
    private var vy = 0f
    private var airborne = false
    private var rotating = false
    private var rot = 0f
    private var idleTimer = 0f
    private var lastMs = 0L
    private var strobe = 0f
    private val skyline = FloatArray(SKY_BARS)

    // Beat tempo estimate: spikes go to the column a whole number of beats
    // away from the cube, so the jump apex lands right on a beat.
    private var lastBeatMs = 0L
    private var beatPeriod = 0.5f

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
        // must not change pace mid-flight.
        val speedTarget = w * (if (idle) 0.30f else 0.32f + 0.22f * rms)
        spd = if (spd == 0f) speedTarget else spd * 0.985f + speedTarget * 0.015f
        val speed = spd
        val block = w / 22f

        while (levels.size < TAPE) appendColumn()
        scroll += speed * dt
        while (scroll >= block) {
            levels.removeFirst()
            spikeAt.removeFirst()
            appendColumn()
            scroll -= block
        }

        val base = h * 0.74f
        fun colOf(x: Float): Int = ((x + scroll) / block).toInt().coerceIn(0, levels.size - 1)
        fun colX(c: Int): Float = (c + 0.5f) * block - scroll
        fun groundY(x: Float): Float = base - levels[colOf(x)] * block

        if (env.beat) strobe = 1f

        // ---- Far plane: the concert ----
        drawStage(canvas, env, w, h, palette, rms, idle)

        // ---- Beat -> spike on the tape ----
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
                val n = ceil(((w + block) - cubeX) / beatDist)
                val k = colOf(cubeX + n * beatDist)
                for (off in intArrayOf(0, 1, -1, 2, -2, 3, -3)) {
                    val c = k + off
                    if (c in 0 until levels.size && canPlaceSpike(c) && colX(c) > cubeX + speed * jumpT) {
                        spikeAt[c] = true
                        lastObstacle = maxOf(lastObstacle, genCol - levels.size + c)
                        break
                    }
                }
            }
        }

        // ---- Cube: jumps spikes and walls, falls off ledges ----
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
                val curLevel = levels[colOf(cubeX)]
                var c = colOf(cubeX) + 1
                while (c < levels.size) {
                    val edge = c * block - scroll - cubeX      // distance to column edge
                    if (edge > trigger + block) break
                    val center = colX(c) - cubeX               // distance to column center
                    if (spikeAt[c] && center > 0f && center <= trigger) {
                        jump = true
                        break
                    }
                    if (levels[c] > curLevel && edge > 0f && edge <= trigger) {
                        jump = true
                        break
                    }
                    c++
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
        var x = -scroll
        groundPath.moveTo(x, base - levels[0] * block)
        for (c in levels.indices) {
            val y = base - levels[c] * block
            groundPath.lineTo(x, y)
            groundPath.lineTo(x + block, y)
            x += block
            if (x > w + block) break
        }
        fillPath.set(groundPath)
        fillPath.lineTo(x, h)
        fillPath.lineTo(-scroll, h)
        fillPath.close()
        canvas.drawPath(fillPath, groundFill)
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

        // ---- Spikes: column flags, exactly one tile, same style as track ----
        spikePaint.color = dim(palette[56], 0.42f)
        for (c in levels.indices) {
            if (!spikeAt[c]) continue
            val sx = colX(c)
            if (sx < -block || sx > w + block) continue
            val sy = base - levels[c] * block
            spikePath.reset()
            spikePath.moveTo(sx - block / 2f, sy)
            spikePath.lineTo(sx, sy - block)
            spikePath.lineTo(sx + block / 2f, sy)
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

    /** New tape column. A terrain step is an obstacle and respects [GAP]. */
    private fun appendColumn() {
        var lvl = lastLevel
        if (genCol - lastObstacle >= GAP) {
            val want = (smoothBass * 3.4f).toInt().coerceIn(0, 3)
            val step = (want - lastLevel).coerceIn(-1, 1)
            if (step != 0) {
                lvl += step
                lastObstacle = genCol
            }
        }
        lastLevel = lvl
        levels.addLast(lvl)
        spikeAt.addLast(false)
        genCol++
    }

    /** A spike needs GAP clear columns around it: no steps, no other spikes. */
    private fun canPlaceSpike(c: Int): Boolean {
        val from = (c - GAP).coerceAtLeast(1)
        val to = (c + GAP).coerceAtMost(levels.size - 1)
        for (j in from..to) {
            if (spikeAt[j]) return false
            if (levels[j] != levels[j - 1]) return false
        }
        return true
    }

    /** Distant stage: equalizer skyline, spotlight beams, beat strobe. */
    private fun drawStage(
        canvas: Canvas, env: PaintEnv, w: Float, h: Float,
        palette: IntArray, rms: Float, idle: Boolean
    ) {
        val horizon = h * 0.56f

        if (strobe > 0f) {
            stagePaint.color = palette[20]
            stagePaint.alpha = (strobe * 70f).toInt()
            canvas.drawCircle(w * 0.62f, horizon - h * 0.10f, h * 0.16f + strobe * h * 0.06f, stagePaint)
        }

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
        stagePaint.alpha = 70
        canvas.drawRect(0f, horizon, w, horizon + 3f, stagePaint)
    }

    private fun dim(c: Int, k: Float): Int = Color.rgb(
        (Color.red(c) * k).toInt(),
        (Color.green(c) * k).toInt(),
        (Color.blue(c) * k).toInt()
    )

    companion object {
        private const val SKY_BARS = 28

        /** Tape length in columns (~2.7 screens at 22 columns per screen). */
        private const val TAPE = 60

        /**
         * Minimum distance between any two obstacles, in columns. A full jump
         * covers ~4.2 columns, so 8 guarantees the cube always lands on flat
         * ground with room to spare before the next obstacle.
         */
        private const val GAP = 8
    }
}
