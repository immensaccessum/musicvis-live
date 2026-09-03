package com.musicvis.live

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.core.content.edit

object HistogramColors {
    const val PREFS = "histogram_colors"
    private const val TOP = "top"
    private const val MID = "mid"
    private const val BOT = "bot"
    private const val ASSET = "asset"
    private const val VER = "ver"
    const val ASSET_CUSTOM = "custom"
    const val ASSET_ICE = "ice"
    const val ASSET_FIRE = "fire"

    data class Preset(
        val id: String,
        val labelRes: Int,
        val top: Int,
        val mid: Int,
        val bot: Int,
        val asset: String = ASSET_CUSTOM
    )

    private val ICE_TOP = Color.rgb(17, 17, 255)
    private val ICE_MID = Color.rgb(241, 241, 255)
    private val ICE_BOT = Color.rgb(3, 3, 255)
    private val FIRE_TOP = Color.parseColor("#FFE14D")
    private val FIRE_MID = Color.parseColor("#E53935")
    private val FIRE_BOT = Color.parseColor("#FFE14D")

    val PRESETS = listOf(
        Preset("ice_orig", R.string.preset_ice_orig, ICE_TOP, ICE_MID, ICE_BOT, ASSET_ICE),
        Preset("fire_orig", R.string.preset_fire_orig, FIRE_TOP, FIRE_MID, FIRE_BOT, ASSET_FIRE),
        Preset("ice", R.string.preset_ice, Color.parseColor("#C5CAFF"), Color.parseColor("#5C6BC0"), Color.parseColor("#1A237E")),
        Preset("violet", R.string.preset_violet, Color.parseColor("#E1BEE7"), Color.parseColor("#7E57C2"), Color.parseColor("#1A237E")),
        Preset("neon", R.string.preset_neon, Color.parseColor("#E0FF7A"), Color.parseColor("#FF00E5"), Color.parseColor("#7A00FF")),
        Preset("matrix", R.string.preset_matrix, Color.parseColor("#B9FFB0"), Color.parseColor("#00C853"), Color.parseColor("#003300")),
        Preset("gold", R.string.preset_gold, Color.parseColor("#FFF8E1"), Color.parseColor("#FFC107"), Color.parseColor("#E65100")),
        Preset("mono", R.string.preset_mono, Color.parseColor("#FFFFFF"), Color.parseColor("#9E9E9E"), Color.parseColor("#FFFFFF")),
        Preset("ocean", R.string.preset_ocean, Color.parseColor("#B2EBF2"), Color.parseColor("#00838F"), Color.parseColor("#004D40")),
    )

    fun load(context: Context): IntArray {
        migrate(context)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return intArrayOf(
            p.getInt(TOP, ICE_TOP),
            p.getInt(MID, ICE_MID),
            p.getInt(BOT, ICE_BOT)
        )
    }

    fun loadAsset(context: Context): String {
        migrate(context)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ASSET, ASSET_ICE) ?: ASSET_ICE
    }

    fun save(context: Context, top: Int, mid: Int, bot: Int, asset: String = ASSET_CUSTOM) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(TOP, top)
            putInt(MID, mid)
            putInt(BOT, bot)
            putString(ASSET, asset)
            putInt(VER, 2)
        }
    }

    fun loadTexture(context: Context): Bitmap {
        val asset = loadAsset(context)
        val res = when (asset) {
            ASSET_ICE -> R.drawable.ice
            ASSET_FIRE -> R.drawable.fire
            else -> null
        }
        val deg = cycleDegrees(context)
        if (res != null) {
            val bmp = BitmapFactory.decodeResource(context.resources, res)
            return if (deg == 0f) bmp else hueShiftBitmap(bmp, deg)
        }
        val c = loadEffective(context)
        return gradientBitmap(c[0], c[1], c[2])
    }

    /**
     * Saved colors with the auto color-cycle applied (if enabled). Renderers
     * use this; the editor screen keeps the pure [load].
     */
    fun loadEffective(context: Context): IntArray {
        val c = load(context)
        val deg = cycleDegrees(context)
        if (deg == 0f) return c
        return intArrayOf(hueShift(c[0], deg), hueShift(c[1], deg), hueShift(c[2], deg))
    }

    /** Palette identity WITHOUT the auto-cycle: for system color notifications. */
    fun baseKey(context: Context): String {
        val c = load(context)
        return "${loadAsset(context)}:${c[0]}:${c[1]}:${c[2]}"
    }

    /**
     * Auto color-cycle: a slow full rotation of the hue circle (~26 s),
     * quantized to 4-degree steps so palette caches refresh only ~3.5x/s.
     */
    private fun cycleDegrees(context: Context): Float {
        if (!FeaturePrefs.colorCycleOn(context)) return 0f
        return (((android.os.SystemClock.uptimeMillis() / 290) % 90) * 4).toFloat()
    }

    fun hueShift(color: Int, deg: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + deg) % 360f
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    private fun hueShiftBitmap(src: Bitmap, deg: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) px[i] = hueShift(px[i], deg)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    fun wallpaperColors(context: Context): WallpaperColors? {
        if (Build.VERSION.SDK_INT < 27) return null
        val c = load(context)
        return WallpaperColors.fromBitmap(gradientBitmap(c[0], c[1], c[2], 128))
    }

    fun textureKey(context: Context): String {
        return "${baseKey(context)}:h${cycleDegrees(context).toInt()}"
    }

    /** Current gradient sampled into n colors (u = 0..1 across the bar). */
    fun palette(context: Context, n: Int = 256): IntArray {
        val tex = loadTexture(context)
        val out = IntArray(n)
        val w = tex.width
        for (i in 0 until n) {
            out[i] = tex.getPixel(i * (w - 1) / (n - 1).coerceAtLeast(1), 0)
        }
        return out
    }

    private fun migrate(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getInt(VER, 0) >= 2) return
        val top = p.getInt(TOP, FIRE_TOP)
        val mid = p.getInt(MID, FIRE_MID)
        val bot = p.getInt(BOT, FIRE_BOT)
        val stillFireDefault = top == FIRE_TOP && mid == FIRE_MID && bot == FIRE_BOT
        p.edit {
            if (stillFireDefault || !p.contains(TOP)) {
                putInt(TOP, ICE_TOP)
                putInt(MID, ICE_MID)
                putInt(BOT, ICE_BOT)
                putString(ASSET, ASSET_ICE)
            }
            putInt(VER, 2)
        }
    }

    fun gradientBitmap(top: Int, mid: Int, bot: Int, width: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width)
        val half = (width - 1) / 2f
        for (i in 0 until width) {
            val t = i / half
            pixels[i] = if (t <= 1f) lerpColor(top, mid, t) else lerpColor(mid, bot, t - 1f)
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, 1)
        return bmp
    }

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val r = Color.red(a) + ((Color.red(b) - Color.red(a)) * u).toInt()
        val g = Color.green(a) + ((Color.green(b) - Color.green(a)) * u).toInt()
        val bl = Color.blue(a) + ((Color.blue(b) - Color.blue(a)) * u).toInt()
        return Color.rgb(r, g, bl)
    }
}
