package com.musicvis.live.wallpapers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.musicvis.live.PaletteCache
import kotlin.math.sin

/**
 * Geometry-Dash style auto-runner with two generators:
 *  1) Far tape: a solid terrace (bass height). No pits.
 *  2) Live rewrite: the current beat carves a gap *right in front of the
 *     cube* and plants a landing. Silence fills those gaps back in.
 * Columns under the cube are never deleted.
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
    private var scroll = 0f
    private var lastLevel = 1

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
    private var pendingJumps = 0
    private var musicOn = false
    private var musicRms = 0f
    private var musicKick = 0f

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

        musicOn = !idle
        if (!idle) {
            musicRms = rms
            musicKick = audio.kick
            val bassTarget = audio.bass
            smoothBass = if (bassTarget > smoothBass) bassTarget else smoothBass * 0.9f
        } else {
            smoothBass = 0f
        }

        if (env.beat && !idle) {
            if (lastBeatMs != 0L) {
                val iv = (env.timeMs - lastBeatMs) / 1000f
                if (iv in 0.25f..1.4f) beatPeriod = beatPeriod * 0.65f + iv * 0.35f
            }
            lastBeatMs = env.timeMs
            strobe = 1f
            pendingJumps = (pendingJumps + 1).coerceAtMost(3)
        }

        val block = w / 18f
        val period = (if (idle) 0.5f else beatPeriod).coerceIn(0.28f, 1.1f)
        val speedTarget = TPB * block / period
        spd = if (spd == 0f) speedTarget else spd * 0.9f + speedTarget * 0.1f
        val speed = spd

        while (levels.size < TAPE) appendSolid()
        scroll += speed * dt
        while (scroll >= block) {
            levels.removeFirst()
            spikeAt.removeFirst()
            padAt.removeFirst()
            appendSolid()
            scroll -= block
        }

        val base = h * 0.76f
        fun colOf(x: Float): Int = ((x + scroll) / block).toInt().coerceIn(0, levels.size - 1)
        fun colX(c: Int): Float = (c + 0.5f) * block - scroll
        fun floorY(lvl: Int): Float = if (lvl == PIT) h * 2f else base - lvl * block
        fun safe(c: Int) = c in levels.indices && levels[c] != PIT && !spikeAt[c]

        val cubeX = w * 0.26f
        val here = colOf(cubeX)
        reshapeAhead(here)

        drawStage(canvas, env, w, h, palette, rms, idle)

        val cube = block * 1.05f
        fun landingAfter(from: Int): Int {
            var i = (from + 1).coerceAtMost(levels.size - 1)
            while (i < levels.size && !safe(i)) i++
            if (i >= levels.size || !safe(i)) {
                val land = (from + TPB).coerceIn(from + 1, levels.size - 1)
                fillCell(land, lastLevel.coerceAtLeast(1))
                return land
            }
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

        val groundNow = floorY(if (levels[here] == PIT) lastLevel else levels[here])
        if (cubeBottom == 0f) cubeBottom = groundNow
        if (!airborne) {
            val nxt = (here + 1).coerceAtMost(levels.size - 1)
            val dangerAhead = nxt > here && (
                levels[nxt] == PIT || spikeAt[nxt] ||
                    (levels[nxt] != PIT && levels[nxt] != levels[here])
                )
            if (!safe(here) || padAt[here] || dangerAhead) {
                launch(here)
            } else {
                cubeBottom = floorY(levels[here])
            }
        }
        if (airborne) {
            cubeBottom -= vy * dt
            vy -= grav * dt
            if (rotating) rot += 180f / jumpT * dt
            val c = colOf(cubeX)
            val floor = if (safe(c)) floorY(levels[c]) else h * 2f
            if (vy < 0f && cubeBottom >= floor && safe(c)) {
                cubeBottom = floor
                vy = 0f
                rot = 0f
                airborne = false
                rotating = false
            } else if (!safe(c) && cubeBottom > base + block) {
                // Emergency floor: spawn a block under the cube instead of falling away.
                fillCell(c, lastLevel.coerceAtLeast(1))
                cubeBottom = floorY(levels[c])
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

    /** Far generator: only a solid terrace. Gaps are stamped live. */
    private fun appendSolid() {
        val lvl = if (musicOn) (smoothBass * 4.2f).toInt().coerceIn(0, 4) else 1
        lastLevel = lvl
        levels.addLast(lvl)
        spikeAt.addLast(false)
        padAt.addLast(false)
    }

    private fun fillCell(c: Int, level: Int) {
        if (c !in levels.indices) return
        levels[c] = level
        spikeAt[c] = false
        padAt[c] = false
        lastLevel = level
    }

    /**
     * Second pass: rewrite tiles already on the tape, starting one cell
     * in front of the cube. Never delete the column under him.
     */
    private fun reshapeAhead(here: Int) {
        if (levels.isEmpty()) return
        val want = if (musicOn) (smoothBass * 4.2f).toInt().coerceIn(0, 4) else 1
        lastLevel = want

        if (!musicOn) {
            for (i in here until levels.size) fillCell(i, 1)
            return
        }

        val hold = if (airborne) {
            var i = (here + 1).coerceAtMost(levels.size - 1)
            while (i < levels.size && (levels[i] == PIT || spikeAt[i])) i++
            (i + 1).coerceAtMost(levels.size)
        } else {
            (here + 1).coerceAtMost(levels.size)
        }

        for (i in hold until levels.size) {
            if (levels[i] != PIT) levels[i] = want
        }

        if (airborne || pendingJumps == 0) return
        if (here + 2 + TPB >= levels.size) return
        pendingJumps--
        val takeoff = here + 1
        val pad = musicKick > 0.45f && musicRms > 0.4f
        levels[takeoff] = want
        spikeAt[takeoff] = !pad
        padAt[takeoff] = pad
        val gap = if (pendingJumps > 0 && musicRms > 0.55f) {
            pendingJumps--
            TPB * 2 - 1
        } else {
            TPB - 1
        }
        for (i in 1..gap) {
            val c = takeoff + i
            if (c < levels.size - 1) {
                levels[c] = PIT
                spikeAt[c] = false
                padAt[c] = false
            }
        }
        val land = (takeoff + gap + 1).coerceAtMost(levels.size - 1)
        fillCell(land, want)
        for (i in land + 1 until (land + 4).coerceAtMost(levels.size)) {
            if (levels[i] == PIT) fillCell(i, want)
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

    companion object {
        private const val SKY_BARS = 28
        private const val TRAIL = 26
        private const val PIT = -100
        private const val TAPE = 36
        private const val TPB = 3
    }
}
