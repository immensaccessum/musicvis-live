package com.musicvis.live.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BarGlRenderer {
    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aUv = 0
    private var uOff = 0
    private var uMul = 0
    private var buf: FloatBuffer? = null
    private val verts = FloatArray(BARS * 6 * 4)
    private var ready = false
    private var texKey: String? = null

    private var bgProgram = 0
    private var aBgPos = 0
    private var aBgCol = 0
    private var bgBuf: FloatBuffer? = null
    private val bgVerts = FloatArray(12 * 6)

    fun init() {
        if (ready) return
        val vs = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            uniform vec2 uOff;
            void main() {
              gl_Position = vec4(aPos.x + uOff.x, aPos.y + uOff.y, 0.0, 1.0);
              vUv = aUv;
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform float uMul;
            void main() {
              vec4 c = texture2D(uTex, vUv);
              gl_FragColor = vec4(c.rgb * uMul, c.a);
            }
        """.trimIndent()
        program = link(vs, fs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uOff = GLES20.glGetUniformLocation(program, "uOff")
        uMul = GLES20.glGetUniformLocation(program, "uMul")
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        val bgVs = """
            attribute vec2 aPos;
            attribute vec4 aCol;
            varying vec4 vCol;
            void main() {
              gl_Position = vec4(aPos, 0.0, 1.0);
              vCol = aCol;
            }
        """.trimIndent()
        val bgFs = """
            precision mediump float;
            varying vec4 vCol;
            void main() {
              gl_FragColor = vCol;
            }
        """.trimIndent()
        bgProgram = link(bgVs, bgFs)
        aBgPos = GLES20.glGetAttribLocation(bgProgram, "aPos")
        aBgCol = GLES20.glGetAttribLocation(bgProgram, "aCol")
        bgBuf = ByteBuffer.allocateDirect(bgVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        ready = true
    }

    fun uploadTexture(bitmap: Bitmap, key: String) {
        if (key == texKey) return
        texKey = key
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun draw(
        heights: FloatArray,
        tiltX: Float,
        tiltY: Float,
        bgColors: IntArray? = null,
        bgLevel: Float = 1f,
        trails: FloatArray? = null,
        flash: Float = 0f
    ) {
        if (!ready) return
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (bgColors != null && bgColors.size >= 3) drawBackground(bgColors, bgLevel)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uOff, tiltX * 0.08f, tiltY * 0.06f)
        if (trails != null) drawBars(trails, 0.32f)
        drawBars(heights, 1f)
        if (flash > 0.02f) drawFlash(flash)
    }

    private fun drawBars(heights: FloatArray, mul: Float) {
        GLES20.glUniform1f(uMul, mul)
        val vp = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0)
        val px = 4f / vp[3].coerceAtLeast(1)
        val n = BARS.coerceAtMost(heights.size)
        var o = 0
        for (i in 0 until n) {
            val h = heights[i].coerceIn(px, 0.95f)
            val x0 = -1f + i * 2f / n
            val x1 = -1f + (i + 1) * 2f / n
            o = quad(o, x0, h, x1, -h)
        }
        val fb = buf!!
        fb.position(0)
        fb.put(verts, 0, o)
        fb.position(0)
        val stride = 16
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, fb)
        fb.position(2)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, fb)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n * 6)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    /**
     * Fullscreen vertical gradient top->mid->bottom. [level] 0..1 breathes the
     * brightness with the music (0.45x when silent, 1x at full loudness).
     */
    private fun drawBackground(colors: IntArray, level: Float) {
        val mul = 0.45f + 0.55f * level.coerceIn(0f, 1f)
        var i = 0
        fun v(x: Float, y: Float, c: Int) {
            bgVerts[i++] = x
            bgVerts[i++] = y
            bgVerts[i++] = (c shr 16 and 0xFF) / 255f * mul
            bgVerts[i++] = (c shr 8 and 0xFF) / 255f * mul
            bgVerts[i++] = (c and 0xFF) / 255f * mul
            bgVerts[i++] = 1f
        }
        val top = colors[0]
        val mid = colors[1]
        val bot = colors[2]
        // Top half: y in [1, 0]
        v(-1f, 1f, top); v(-1f, 0f, mid); v(1f, 1f, top)
        v(1f, 1f, top); v(-1f, 0f, mid); v(1f, 0f, mid)
        // Bottom half: y in [0, -1]
        v(-1f, 0f, mid); v(-1f, -1f, bot); v(1f, 0f, mid)
        v(1f, 0f, mid); v(-1f, -1f, bot); v(1f, -1f, bot)
        drawColorQuads(12, blend = false)
    }

    /** Beat flash: translucent white quad over the whole frame. */
    private fun drawFlash(alpha: Float) {
        var i = 0
        fun v(x: Float, y: Float) {
            bgVerts[i++] = x
            bgVerts[i++] = y
            bgVerts[i++] = 1f
            bgVerts[i++] = 1f
            bgVerts[i++] = 1f
            bgVerts[i++] = alpha.coerceIn(0f, 1f) * 0.6f
        }
        v(-1f, 1f); v(-1f, -1f); v(1f, 1f)
        v(1f, 1f); v(-1f, -1f); v(1f, -1f)
        drawColorQuads(6, blend = true)
    }

    private fun drawColorQuads(count: Int, blend: Boolean) {
        val fb = bgBuf!!
        fb.position(0)
        fb.put(bgVerts, 0, count * 6)
        GLES20.glUseProgram(bgProgram)
        if (blend) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
        val stride = 24
        fb.position(0)
        GLES20.glEnableVertexAttribArray(aBgPos)
        GLES20.glVertexAttribPointer(aBgPos, 2, GLES20.GL_FLOAT, false, stride, fb)
        fb.position(2)
        GLES20.glEnableVertexAttribArray(aBgCol)
        GLES20.glVertexAttribPointer(aBgCol, 4, GLES20.GL_FLOAT, false, stride, fb)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(aBgPos)
        GLES20.glDisableVertexAttribArray(aBgCol)
        if (blend) GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun quad(o: Int, x0: Float, y0: Float, x1: Float, y1: Float): Int {
        var i = o
        fun v(x: Float, y: Float, u: Float, v: Float) {
            verts[i++] = x; verts[i++] = y; verts[i++] = u; verts[i++] = v
        }
        v(x0, y0, 0f, 0.5f); v(x0, y1, 1f, 0.5f); v(x1, y0, 0f, 0.5f)
        v(x1, y0, 0f, 0.5f); v(x0, y1, 1f, 0.5f); v(x1, y1, 1f, 0.5f)
        return i
    }

    private fun link(vsSrc: String, fsSrc: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vsSrc))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fsSrc))
        GLES20.glLinkProgram(p)
        GLES20.glUseProgram(p)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uTex"), 0)
        return p
    }

    companion object {
        const val BARS = 512
    }
}
