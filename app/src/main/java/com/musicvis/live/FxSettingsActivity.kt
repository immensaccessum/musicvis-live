package com.musicvis.live

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.musicvis.live.databinding.ActivityFxSettingsBinding

/** Background and party-effect toggles; applied live by all wallpaper modes. */
class FxSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityFxSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bgGradient.isChecked = FeaturePrefs.bgGradient(this)
        binding.fxFlash.isChecked = FeaturePrefs.fxFlash(this)
        binding.fxCycle.isChecked = FeaturePrefs.fxColorCycle(this)
        binding.fxWaves.isChecked = FeaturePrefs.fxWaves(this)
        binding.fxTrails.isChecked = FeaturePrefs.fxTrails(this)
        binding.party.isChecked = FeaturePrefs.party(this)

        val op = FeaturePrefs.fxFlashOpacity(this)
        binding.fxFlashOpacity.value = op.toFloat()
        binding.fxFlashOpacityLabel.text = getString(R.string.fx_flash_opacity, op)
        binding.fxFlashOpacity.addOnChangeListener { _, value, fromUser ->
            val n = value.toInt()
            binding.fxFlashOpacityLabel.text = getString(R.string.fx_flash_opacity, n)
            if (fromUser) FeaturePrefs.setFxFlashOpacity(this, n)
        }

        val waveOp = FeaturePrefs.fxWavesOpacity(this)
        binding.fxWavesOpacity.value = waveOp.toFloat()
        binding.fxWavesOpacityLabel.text = getString(R.string.fx_waves_opacity, waveOp)
        binding.fxWavesOpacity.addOnChangeListener { _, value, fromUser ->
            val n = value.toInt()
            binding.fxWavesOpacityLabel.text = getString(R.string.fx_waves_opacity, n)
            if (fromUser) FeaturePrefs.setFxWavesOpacity(this, n)
        }

        binding.bgGradient.setOnCheckedChangeListener { _, v -> FeaturePrefs.setBgGradient(this, v) }
        binding.fxFlash.setOnCheckedChangeListener { _, v -> FeaturePrefs.setFxFlash(this, v) }
        binding.fxCycle.setOnCheckedChangeListener { _, v -> FeaturePrefs.setFxColorCycle(this, v) }
        binding.fxWaves.setOnCheckedChangeListener { _, v -> FeaturePrefs.setFxWaves(this, v) }
        binding.fxTrails.setOnCheckedChangeListener { _, v -> FeaturePrefs.setFxTrails(this, v) }
        binding.party.setOnCheckedChangeListener { _, v -> FeaturePrefs.setParty(this, v) }
    }
}
