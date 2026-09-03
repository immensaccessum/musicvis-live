package com.musicvis.live

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Reference screen: what every visualizer mode shows and how it works. */
class ModesInfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 96, 64, 96)
        }

        list.addView(TextView(this).apply {
            text = getString(R.string.info_title)
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        list.addView(body(R.string.info_general))

        section(list, R.string.group_volume)
        entry(list, R.string.wallpaper_ice, R.string.info_ice)
        entry(list, R.string.wallpaper_classic, R.string.info_classic)
        entry(list, R.string.wallpaper_scope, R.string.info_scope)
        entry(list, R.string.wallpaper_dots, R.string.info_dots)
        entry(list, R.string.wallpaper_vu, R.string.info_vu)
        entry(list, R.string.wallpaper_radial, R.string.info_radial)
        entry(list, R.string.wallpaper_lightshow, R.string.info_lightshow)

        section(list, R.string.group_no_volume)
        entry(list, R.string.wallpaper_fft, R.string.info_fft)
        entry(list, R.string.wallpaper_octave, R.string.info_octave)
        entry(list, R.string.wallpaper_waterfall, R.string.info_waterfall)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF101014.toInt())
            addView(list)
        })
    }

    private fun section(parent: LinearLayout, titleRes: Int) {
        parent.addView(TextView(this).apply {
            text = getString(titleRes)
            textSize = 13f
            setTextColor(0xFF8A90A8.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 56, 0, 0)
        })
    }

    private fun entry(parent: LinearLayout, nameRes: Int, bodyRes: Int) {
        parent.addView(TextView(this).apply {
            text = getString(nameRes)
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 40, 0, 0)
        })
        parent.addView(body(bodyRes))
    }

    private fun body(res: Int) = TextView(this).apply {
        text = getString(res)
        textSize = 14f
        setTextColor(0xFFB0B4C8.toInt())
        setPadding(0, 12, 0, 0)
        setLineSpacing(0f, 1.15f)
    }
}
