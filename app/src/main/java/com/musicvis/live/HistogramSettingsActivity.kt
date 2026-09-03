package com.musicvis.live

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.musicvis.live.databinding.ActivityHistogramSettingsBinding

class HistogramSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistogramSettingsBinding
    private var top = 0
    private var mid = 0
    private var bot = 0
    private var slot = 1
    private var asset = HistogramColors.ASSET_ICE
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistogramSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val loaded = HistogramColors.load(this)
        top = loaded[0]
        mid = loaded[1]
        bot = loaded[2]
        asset = HistogramColors.loadAsset(this)

        buildPresets()

        binding.slotTop.setOnClickListener { selectSlot(0) }
        binding.slotMid.setOnClickListener { selectSlot(1) }
        binding.slotBot.setOnClickListener { selectSlot(2) }
        binding.mirror.setOnClickListener {
            bot = top
            asset = HistogramColors.ASSET_CUSTOM
            persist()
            refreshUi()
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updating) return
                applyHsv()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        binding.hue.setOnSeekBarChangeListener(listener)
        binding.sat.setOnSeekBarChangeListener(listener)
        binding.value.setOnSeekBarChangeListener(listener)

        selectSlot(1)
        refreshUi()
    }

    // OriginOS corrupts the rendering of a tapped row with a custom gradient
    // background (pressed state deforms/hides it; a ripple even OOM-crashes).
    // A freshly built list always renders correctly, so the rows use manual
    // touch handling (no pressed state) and the whole list is rebuilt after
    // every selection.
    private fun buildPresets() {
        binding.presets.removeAllViews()
        HistogramColors.PRESETS.forEach { preset ->
            val label = TextView(this).apply {
                text = getString(preset.labelRes)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(24, 40, 24, 40)
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(preset.top, preset.mid, preset.bot)
                ).apply {
                    cornerRadius = 24f
                    setSize(1080, 140)
                }
                setTextColor(if (luma(preset.mid) > 140) Color.BLACK else Color.WHITE)
            }
            val cell = FrameLayout(this).apply {
                addView(
                    label,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                setOnTouchListener { v, e ->
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        v.playSoundEffect(SoundEffectConstants.CLICK)
                        top = preset.top
                        mid = preset.mid
                        bot = preset.bot
                        asset = preset.asset
                        persist()
                        refreshUi()
                        binding.presets.post { buildPresets() }
                    }
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 8
            binding.presets.addView(cell, lp)
        }
    }

    private fun selectSlot(index: Int) {
        slot = index
        binding.slotLabel.text = when (index) {
            0 -> getString(R.string.color_tips)
            1 -> getString(R.string.color_center)
            else -> getString(R.string.color_bottom)
        }
        slidersFrom(currentColor())
        highlightSlots()
    }

    private fun currentColor() = when (slot) {
        0 -> top
        1 -> mid
        else -> bot
    }

    private fun applyHsv() {
        val hsv = floatArrayOf(
            binding.hue.progress.toFloat(),
            binding.sat.progress / 100f,
            binding.value.progress / 100f
        )
        val c = Color.HSVToColor(hsv)
        when (slot) {
            0 -> top = c
            1 -> mid = c
            else -> bot = c
        }
        asset = HistogramColors.ASSET_CUSTOM
        persist()
        refreshPreview()
        highlightSlots()
    }

    private fun slidersFrom(color: Int) {
        updating = true
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        binding.hue.progress = hsv[0].toInt()
        binding.sat.progress = (hsv[1] * 100).toInt()
        binding.value.progress = (hsv[2] * 100).toInt()
        updating = false
    }

    private fun persist() {
        HistogramColors.save(this, top, mid, bot, asset)
    }

    private fun refreshUi() {
        slidersFrom(currentColor())
        refreshPreview()
        highlightSlots()
    }

    private fun refreshPreview() {
        binding.preview.setImageBitmap(HistogramColors.loadTexture(this))
    }

    private fun highlightSlots() {
        tint(binding.slotTop, top)
        tint(binding.slotMid, mid)
        tint(binding.slotBot, bot)
    }

    private fun tint(button: Button, color: Int) {
        // Tint the existing background instead of replacing it: a custom
        // ColorDrawable has no intrinsic size and trips vivo's press animation.
        button.backgroundTintList = ColorStateList.valueOf(color)
        button.setTextColor(if (luma(color) > 140) Color.BLACK else Color.WHITE)
    }

    private fun luma(color: Int) = ColorUtils.calculateLuminance(color) * 255
}
