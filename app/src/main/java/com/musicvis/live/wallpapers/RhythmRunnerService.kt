package com.musicvis.live.wallpapers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.musicvis.live.PaletteCache
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.random.Random

/**
 * Geometry-Dash style auto-runner. The level is a tape of tiles; spikes and
 * pads are flags on a column so they can never drift off the grid.
 *
 * The generator never emits a staircase. It queues discrete set-pieces
 * driven by the music: spike groups, 1–2 block walls, pits, cliffs, bounce
 * pads. Each piece gets its own jump arc — a hop, a long leap, a high vault.
 * The cube never walks down a step: a drop is always a jump or a fall.
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
    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cubePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundPath = Path()
    private val fillPath = Path()
    private val spikePath = Path()
    private val beamPath = Path()

    private val levels = ArrayDeque<Int>()
    private val spikeAt = ArrayDeque<Boolean>()
    private val padAt = ArrayDeque<Boolean>()
    private val planned = ArrayDeque<Cell>()
    private var scroll = 0f
    private var lastLevel = 1
    private val rnd = Random(System.nanoTime())

    private var smoothBass = 0f
    private var spd = 0f

    private var cubeBottom = 0f
    private var vy = 0f
    private var airborne = false
    private var rotating = false
    private var rot = 0f
    private var jumpT = 0.55f
    private var grav = 0f
    private var lastMs = 0L
    private var strobe = 0f
    private val skyline = FloatArray(SKY_BARS)

    private val trailXs = FloatArray(TRAIL)
    private val trailYs = FloatArray(TRAIL)
    private var trailLen = 0

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

        val bassTarget = if (idle) 0.35f + 0.25f * sin(env.timeMs * 0.0014f) else audio.bass
        smoothBass = if (bassTarget > smoothBass) bassTarget else smoothBass * 0.94f

        val speedTarget = w * (if (idle) 0.38f else 0.40f + 0.28f * rms)
        spd = if (spd == 0f) speedTarget else spd * 0.98f + speedTarget * 0.02f
        val speed = spd
        val block = w / 18f

        while (levels.size < TAPE) appendColumn()
        scroll += speed * dt
        while (scroll >= block) {
            levels.removeFirst()
            spikeAt.removeFirst()
            padAt.removeFirst()
            appendColumn()
            scroll -= block
        }

        val base = h * 0.76f
        fun colOf(x: Float): Int = ((x + scroll) / block).toInt().coerceIn(0, levels.size - 1)
        fun colX(c: Int): Float = (c + 0.5f) * block - scroll
        fun floorY(lvl: Int): Float = if (lvl == PIT) h * 2f else base - lvl * block
        fun groundY(x: Float): Float = floorY(levels[colOf(x)])

        if (env.beat) strobe = 1f
        drawStage(canvas, env, w, h, palette, rms, idle)

        val cubeX = w * 0.26f
        if (env.beat && !idle) {
            if (lastBeatMs != 0L) {
                val iv = (env.timeMs - lastBeatMs) / 1000f
                if (iv in 0.25f..1.5f) beatPeriod = beatPeriod * 0.7f + iv * 0.3f
            }
            lastBeatMs = env.timeMs
            val group = 1 + (rms * 2.6f).toInt().coerceAtMost(2)
            val beatDist = (beatPeriod * speed).coerceAtLeast(1f)
            val n = ceil(((w + block) - cubeX) / beatDist)
            val k = colOf(cubeX + n * beatDist)
            for (off in intArrayOf(0, 1, -1, 2, -2)) {
                val c = k + off
                if (c >= 0 && c + group <= levels.size &&
                    canPlaceSpikes(c, group) && colX(c) > cubeX + speed * 0.45f
                ) {
                    for (j in 0 until group) spikeAt[c + j] = true
                    break
                }
            }
        }

        val cube = block * 1.05f
        val groundNow = groundY(cubeX)
        if (cubeBottom == 0f) cubeBottom = groundNow
        if (!airborne) {
            when {
                groundNow < cubeBottom - 2f -> {
                    // Floor vanished (pit/cliff). Fall — never walk down.
                    airborne = true
                    rotating = false
                    jumpT = 0.5f
                    grav = 8f * (5f * block) / (jumpT * jumpT)
                    vy = 0f
                }
                else -> {
                    cubeBottom = groundNow
                    var jump = false
                    var jt = 0.48f
                    var jh = 2.2f * block
                    val look = speed * 0.85f
                    val cur = levels[colOf(cubeX)]
                    if (padAt[colOf(cubeX)] && cur != PIT) {
                        jump = true
                        jt = 0.72f
                        jh = 6.2f * block
                    } else {
                        var c = colOf(cubeX) + 1
                        while (c < levels.size) {
                            val edge = c * block - scroll - cubeX
                            if (edge > look) break
                            val lvl = levels[c]
                            when {
                                padAt[c] -> {
                                    jt = 0.7f
                                    jh = 6f * block
                                    if (edge <= speed * jt / 2f) jump = true
                                    break
                                }
                                spikeAt[c] -> {
                                    var g = 1
                                    while (c + g < levels.size && spikeAt[c + g]) g++
                                    jt = 0.42f + 0.14f * g
                                    jh = (1.8f + 0.9f * g) * block
                                    val trig = (speed * jt / 2f - g * block / 2f).coerceAtLeast(block * 0.25f)
                                    if (edge <= trig) jump = true
                                    break
                                }
                                lvl == PIT -> {
                                    var p = 1
                                    while (c + p < levels.size && levels[c + p] == PIT) p++
                                    jt = (2.2f * p * block / speed).coerceIn(0.48f, 0.85f)
                                    jh = (2.6f + 0.4f * p) * block
                                    val trig = (speed * jt / 2f - p * block / 2f).coerceAtLeast(block * 0.25f)
                                    if (edge <= trig) jump = true
                                    break
                                }
                                lvl > cur -> {
                                    val dl = lvl - cur
                                    jt = 0.50f + 0.12f * dl
                                    jh = (dl + 2.2f) * block
                                    if (edge <= speed * jt / 2f) jump = true
                                    break
                                }
                                lvl < cur && lvl != PIT -> {
                                    // Leap off the cliff instead of walking down the step.
                                    val drop = cur - lvl
                                    jt = 0.40f + 0.08f * drop
                                    jh = (1.4f + 0.4f * drop) * block
                                    if (edge <= speed * jt / 2f) jump = true
                                    break
                                }
                            }
                            c++
                        }
                    }
                    if (jump) {
                        airborne = true
                        rotating = true
                        jumpT = jt
                        grav = 8f * jh / (jt * jt)
                        vy = grav * jt / 2f
                    }
                }
            }
        }
        if (airborne) {
            cubeBottom -= vy * dt
            vy -= grav * dt
            if (rotating) rot += 180f / jumpT * dt
            val floor = groundY(cubeX)
            if (vy < 0f && cubeBottom >= floor && levels[colOf(cubeX)] != PIT) {
                cubeBottom = floor
                vy = 0f
                rot = 0f
                airborne = false
                rotating = false
            }
        }

        val lineColor = mix(palette[44], Color.WHITE, strobe * 0.45f)
        groundFill.color = dim(palette[56], 0.30f)
        groundLine.color = lineColor
        gridPaint.color = Color.argb(34, 255, 255, 255)
        groundPath.reset()
        var x = -scroll
        groundPath.moveTo(x, floorY(levels[0]).coerceAtMost(h + 20f))
        for (c in levels.indices) {
            val y = floorY(levels[c]).coerceAtMost(h + 20f)
            groundPath.lineTo(x, y)
            groundPath.lineTo(x + block, y)
            x += block
            if (x > w + block) break
        }
        fillPath.set(groundPath)
        fillPath.lineTo(x, h + 20f)
        fillPath.lineTo(-scroll, h + 20f)
        fillPath.close()
        canvas.drawPath(fillPath, groundFill)
        var gx = -scroll
        var gc = 0
        while (gx < w + block && gc < levels.size) {
            if (levels[gc] != PIT) canvas.drawLine(gx, floorY(levels[gc]), gx, h, gridPaint)
            gx += block
            gc++
        }
        var gy = base
        while (gy < h) {
            canvas.drawLine(0f, gy, w, gy, gridPaint)
            gy += block
        }
        canvas.drawPath(groundPath, groundLine)

        padPaint.color = mix(palette[20], Color.WHITE, 0.35f + 0.4f * strobe)
        for (c in levels.indices) {
            if (!padAt[c] || levels[c] == PIT) continue
            val px = colX(c)
            val py = floorY(levels[c])
            canvas.drawRoundRect(
                px - block * 0.42f, py - block * 0.18f,
                px + block * 0.42f, py + 4f,
                8f, 8f, padPaint
            )
        }

        spikePaint.color = mix(dim(palette[56], 0.42f), lineColor, strobe * 0.25f)
        for (c in levels.indices) {
            if (!spikeAt[c] || levels[c] == PIT) continue
            val sx = colX(c)
            if (sx < -block || sx > w + block) continue
            val sy = floorY(levels[c])
            spikePath.reset()
            spikePath.moveTo(sx - block / 2f, sy)
            spikePath.lineTo(sx, sy - block)
            spikePath.lineTo(sx + block / 2f, sy)
            spikePath.close()
            canvas.drawPath(spikePath, spikePaint)
            canvas.drawPath(spikePath, groundLine)
        }

        val cy = cubeBottom - cube / 2f
        for (i in 0 until trailLen) trailXs[i] -= speed * dt
        if (trailLen == TRAIL) {
            System.arraycopy(trailXs, 1, trailXs, 0, TRAIL - 1)
            System.arraycopy(trailYs, 1, trailYs, 0, TRAIL - 1)
            trailLen--
        }
        trailXs[trailLen] = cubeX
        trailYs[trailLen] = cy
        trailLen++
        trailPaint.color = palette[30]
        for (i in 0 until trailLen - 1) {
            val f = (i + 1f) / trailLen
            trailPaint.alpha = (f * 70f).toInt()
            val s = cube * (0.25f + 0.35f * f)
            canvas.drawRoundRect(
                trailXs[i] - s / 2f, trailYs[i] - s / 2f,
                trailXs[i] + s / 2f, trailYs[i] + s / 2f,
                s * 0.2f, s * 0.2f, trailPaint
            )
        }
        cubePaint.color = mix(palette[30], Color.WHITE, strobe * 0.3f)
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

    private fun appendColumn() {
        if (planned.isEmpty()) planSetPiece()
        val cell = planned.removeFirst()
        if (cell.level != PIT) lastLevel = cell.level
        levels.addLast(cell.level)
        spikeAt.addLast(cell.spike)
        padAt.addLast(cell.pad)
    }

    /**
     * Rest of [rest] flat columns, then one set-piece. Never a 1-block
     * staircase: height changes are walls, cliffs or pits.
     */
    private fun planSetPiece() {
        val energy = smoothBass.coerceIn(0f, 1f)
        val rest = when {
            energy > 0.65f -> 4
            energy > 0.4f -> 5
            else -> 7
        }
        repeat(rest) { planned.addLast(Cell(lastLevel, spike = false, pad = false)) }

        val roll = rnd.nextFloat()
        when {
            energy < 0.18f -> {
                if (lastLevel > 1 && roll < 0.45f) {
                    lastLevel = (lastLevel - 2).coerceAtLeast(0)
                    planned.addLast(Cell(lastLevel, spike = false, pad = false))
                }
            }
            roll < 0.22f -> {
                val up = if (energy > 0.55f && lastLevel <= 2) 2 else 1
                lastLevel = (lastLevel + up).coerceAtMost(4)
                planned.addLast(Cell(lastLevel, spike = false, pad = false))
            }
            roll < 0.44f && lastLevel > 0 -> {
                lastLevel = (lastLevel - (if (energy > 0.5f) 2 else 1)).coerceAtLeast(0)
                planned.addLast(Cell(lastLevel, spike = false, pad = false))
            }
            roll < 0.66f -> {
                val wide = 2 + if (energy > 0.5f) rnd.nextInt(2) else 0
                repeat(wide) { planned.addLast(Cell(PIT, spike = false, pad = false)) }
                if (energy > 0.45f && rnd.nextBoolean()) {
                    lastLevel = (lastLevel + (if (rnd.nextBoolean()) 1 else -1)).coerceIn(0, 4)
                }
            }
            roll < 0.82f -> {
                planned.addLast(Cell(lastLevel, spike = false, pad = true))
            }
            else -> {
                val n = 1 + (energy * 2.2f).toInt().coerceAtMost(2)
                repeat(n) { planned.addLast(Cell(lastLevel, spike = true, pad = false)) }
            }
        }
    }

    private fun canPlaceSpikes(c: Int, g: Int): Boolean {
        val from = (c - 3).coerceAtLeast(1)
        val to = (c + g - 1 + 3).coerceAtMost(levels.size - 1)
        if (c + g >= levels.size) return false
        for (j in from..to) {
            if (spikeAt[j] || padAt[j]) return false
            if (levels[j] == PIT || levels[j] != levels[j - 1]) return false
        }
        return true
    }

    private fun drawStage(
        canvas: Canvas, env: PaintEnv, w: Float, h: Float,
        palette: IntArray, rms: Float, idle: Boolean
    ) {
        val horizon = h * 0.52f
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
            val bh = skyline[i] * h * 0.12f
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

    private fun mix(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )
    }

    private data class Cell(val level: Int, val spike: Boolean, val pad: Boolean)

    companion object {
        private const val SKY_BARS = 28
        private const val TRAIL = 26
        private const val PIT = -100
        private const val TAPE = 60
    }
}
