package com.musicvis.live.wallpapers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.musicvis.live.PaletteCache
import kotlin.math.sin
import kotlin.random.Random

/**
 * Geometry-Dash style auto-runner, cheated onto the beat.
 *
 * Speed is locked so exactly [TPB] tiles pass per beat. Solid floor exists
 * only on beat columns (and short run-up tiles); everything between is
 * carved out. The cube can only land on a beat — takeoff and landing are
 * the metronome. Jump duration is the gap width / speed, so it equals
 * one or two beats by construction.
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
    private var genCol = 0
    private var lastLevel = 1
    private val rnd = Random(System.nanoTime())

    private var smoothBass = 0f
    private var spd = 0f

    private var cubeBottom = 0f
    private var vy = 0f
    private var airborne = false
    private var rotating = false
    private var rot = 0f
    private var jumpT = 0.5f
    private var grav = 0f
    private var lastMs = 0L
    private var strobe = 0f
    private val skyline = FloatArray(SKY_BARS)

    private val trailXs = FloatArray(TRAIL)
    private val trailYs = FloatArray(TRAIL)
    private var trailLen = 0

    private var lastBeatMs = 0L
    private var beatPeriod = 0.5f
    private var phase = 0
    private var phaseLocked = false
    private var pendingJumps = 0

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

        if (env.beat && !idle) {
            if (lastBeatMs != 0L) {
                val iv = (env.timeMs - lastBeatMs) / 1000f
                if (iv in 0.25f..1.4f) beatPeriod = beatPeriod * 0.65f + iv * 0.35f
            }
            lastBeatMs = env.timeMs
            strobe = 1f
            pendingJumps = (pendingJumps + 1).coerceAtMost(5)
        }

        val block = w / 18f
        val period = (if (idle) 0.5f else beatPeriod).coerceIn(0.28f, 1.1f)
        val speedTarget = TPB * block / period
        spd = if (spd == 0f) speedTarget else spd * 0.9f + speedTarget * 0.1f
        val speed = spd

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

        if (env.beat && !idle && !phaseLocked) {
            val absCube = genCol - levels.size + colOf(w * 0.26f)
            phase = (TPB - ((absCube % TPB) + TPB) % TPB) % TPB
            phaseLocked = true
        }

        drawStage(canvas, env, w, h, palette, rms, idle)

        val cubeX = w * 0.26f
        val cube = block * 1.05f
        val groundNow = groundY(cubeX)
        if (cubeBottom == 0f) cubeBottom = groundNow
        val here = colOf(cubeX)
        fun safe(c: Int) = c in levels.indices && levels[c] != PIT && !spikeAt[c]
        fun landingAfter(from: Int): Int {
            var i = (from + 1).coerceAtMost(levels.size - 1)
            while (i < levels.size - 1 && !safe(i)) i++
            return i
        }
        fun launch(from: Int) {
            val land = landingAfter(from)
            val dist = (colX(land) - cubeX).coerceAtLeast(block * 0.85f)
            jumpT = (dist / speed).coerceIn(0.28f, 1.3f)
            val fromLvl = (from downTo 0).firstOrNull { levels[it] != PIT }?.let { levels[it] } ?: lastLevel
            val landLvl = if (levels[land] == PIT) fromLvl else levels[land]
            val climb = (landLvl - fromLvl).coerceAtLeast(0)
            val span = (land - from).coerceAtLeast(1)
            val jh = when {
                from in padAt.indices && padAt[from] -> 5.8f * block
                climb > 0 -> (climb + 2.2f) * block
                else -> (2.8f + 0.2f * span) * block
            }
            airborne = true
            rotating = true
            grav = 8f * jh / (jumpT * jumpT)
            vy = grav * jumpT / 2f
        }
        if (!airborne) {
            val nxt = (here + 1).coerceAtMost(levels.size - 1)
            // Never walk onto a spike or step into a pit. Jump from the safe tile before.
            val dangerAhead = nxt > here && (
                levels[nxt] == PIT || spikeAt[nxt] ||
                    (levels[nxt] != PIT && levels[nxt] != levels[here])
                )
            if (!safe(here) || padAt[here] || dangerAhead) {
                launch(here)
            } else {
                cubeBottom = groundNow
            }
        }
        if (airborne) {
            cubeBottom -= vy * dt
            vy -= grav * dt
            if (rotating) rot += 180f / jumpT * dt
            val c = colOf(cubeX)
            val floor = groundY(cubeX)
            // Refuse to land on a spike or in a pit — keep flying to the next safe tile.
            if (vy < 0f && cubeBottom >= floor && safe(c)) {
                cubeBottom = floor
                vy = 0f
                rot = 0f
                airborne = false
                rotating = false
            } else if (vy < 0f && !safe(c) && cubeBottom > base + block * 2f) {
                // Last-ditch: we dropped under the world. Snap to the next safe tile.
                val land = landingAfter(c)
                cubeBottom = floorY(if (levels[land] == PIT) lastLevel else levels[land])
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
        genCol++
    }

    private fun absPlanned(): Int = genCol + planned.size

    private fun onBeat(abs: Int): Boolean = ((abs + phase) % TPB + TPB) % TPB == 0

    private fun alignToBeat() {
        while (!onBeat(absPlanned())) {
            planned.addLast(Cell(lastLevel, spike = false, pad = false))
        }
    }

    private fun carveToNextBeat() {
        do {
            planned.addLast(Cell(PIT, spike = false, pad = false))
        } while (!onBeat(absPlanned()))
    }

    /**
     * Floor only on beat columns. Between beats the tiles are deleted so
     * the cube has nowhere to stand except on the metronome.
     */
    private fun planSetPiece() {
        alignToBeat()
        val energy = smoothBass.coerceIn(0f, 1f)
        if (pendingJumps > 0) {
            pendingJumps--
            val longJump = pendingJumps > 0 && energy > 0.5f && rnd.nextFloat() < 0.35f
            if (longJump) pendingJumps--
            val pad = energy > 0.45f && rnd.nextFloat() < 0.25f
            planned.addLast(Cell(lastLevel, spike = !pad, pad = pad))
            if (longJump) {
                carveToNextBeat()
                planned.addLast(Cell(PIT, spike = false, pad = false))
            }
            carveToNextBeat()
            shapeLanding(energy)
            planned.addLast(Cell(lastLevel, spike = false, pad = false))
            return
        }
        // No beat waiting: one solid beat, then carve — a rest step, wall or cliff.
        val roll = rnd.nextFloat()
        when {
            energy > 0.4f && roll < 0.35f -> {
                lastLevel = (lastLevel + if (energy > 0.6f) 2 else 1).coerceAtMost(4)
                planned.addLast(Cell(lastLevel, spike = false, pad = false))
            }
            lastLevel > 0 && roll < 0.6f -> {
                planned.addLast(Cell(lastLevel, spike = false, pad = false))
                lastLevel = (lastLevel - if (energy > 0.5f) 2 else 1).coerceAtLeast(0)
            }
            else -> planned.addLast(Cell(lastLevel, spike = false, pad = false))
        }
        carveToNextBeat()
        planned.addLast(Cell(lastLevel, spike = false, pad = false))
    }

    private fun shapeLanding(energy: Float) {
        val roll = rnd.nextFloat()
        when {
            energy > 0.55f && roll < 0.35f ->
                lastLevel = (lastLevel + 1).coerceAtMost(4)
            lastLevel > 0 && roll < 0.6f ->
                lastLevel = (lastLevel - 1).coerceAtLeast(0)
        }
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
        /** Tiles that scroll past per beat. Speed = TPB * block / period. */
        private const val TPB = 3
    }
}
