package com.musicvis.live

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.musicvis.live.audio.AudioEngine
import com.musicvis.live.databinding.ActivityMainBinding
import com.musicvis.live.wallpapers.ClassicHistogramService
import com.musicvis.live.wallpapers.DotsService
import com.musicvis.live.wallpapers.FftHistogramService
import com.musicvis.live.wallpapers.IceWaveService
import com.musicvis.live.wallpapers.LightShowService
import com.musicvis.live.wallpapers.OctaveSpectrumService
import com.musicvis.live.wallpapers.RadialSpectrumService
import com.musicvis.live.wallpapers.ScopeService
import com.musicvis.live.wallpapers.VuMeterService
import com.musicvis.live.wallpapers.WaterfallService

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FeaturePrefs.setIsland(this, false)
        LiveIsland.stop(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.grant.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        binding.openListener.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.setIce.setOnClickListener { pick(IceWaveService::class.java) }
        binding.setClassic.setOnClickListener { pick(ClassicHistogramService::class.java) }
        binding.setFft.setOnClickListener { pick(FftHistogramService::class.java) }
        binding.setOctave.setOnClickListener { pick(OctaveSpectrumService::class.java) }
        binding.setWaterfall.setOnClickListener { pick(WaterfallService::class.java) }
        binding.setVu.setOnClickListener { pick(VuMeterService::class.java) }
        binding.setDots.setOnClickListener { pick(DotsService::class.java) }
        binding.setScope.setOnClickListener { pick(ScopeService::class.java) }
        binding.setRadial.setOnClickListener { pick(RadialSpectrumService::class.java) }
        binding.setLightshow.setOnClickListener { pick(LightShowService::class.java) }
        binding.openLab.setOnClickListener {
            startActivity(Intent(this, FftLabActivity::class.java))
        }
        binding.openColors.setOnClickListener {
            startActivity(Intent(this, HistogramSettingsActivity::class.java))
        }
        binding.openFx.setOnClickListener {
            startActivity(Intent(this, FxSettingsActivity::class.java))
        }
        binding.openInfo.setOnClickListener {
            startActivity(Intent(this, ModesInfoActivity::class.java))
        }
        binding.language.setOnClickListener { showLanguageDialog() }

        binding.micSource.isChecked = FeaturePrefs.micSource(this)
        binding.noteDisplay.isChecked = FeaturePrefs.noteDisplay(this)
        binding.tilt.isChecked = FeaturePrefs.tilt(this)
        binding.nowPlaying.isChecked = FeaturePrefs.nowPlaying(this)
        binding.touchFx.isChecked = FeaturePrefs.touchFx(this)
        binding.haptics.isChecked = FeaturePrefs.haptics(this)

        binding.micSource.setOnCheckedChangeListener { _, v ->
            FeaturePrefs.setMicSource(this, v)
            AudioEngine.get(this).onSourcePrefChanged()
        }
        binding.noteDisplay.setOnCheckedChangeListener { _, v -> FeaturePrefs.setNoteDisplay(this, v) }
        binding.tilt.setOnCheckedChangeListener { _, v -> FeaturePrefs.setTilt(this, v) }
        binding.nowPlaying.setOnCheckedChangeListener { _, v -> FeaturePrefs.setNowPlaying(this, v) }
        binding.touchFx.setOnCheckedChangeListener { _, v -> FeaturePrefs.setTouchFx(this, v) }
        binding.haptics.setOnCheckedChangeListener { _, v -> FeaturePrefs.setHaptics(this, v) }
    }

    private fun showLanguageDialog() {
        val labels = arrayOf(getString(R.string.lang_system), "Русский", "English")
        val tags = arrayOf("", "ru", "en")
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val checked = when {
            current.startsWith("ru") -> 1
            current.startsWith("en") -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.lang_button)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val list = if (tags[which].isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tags[which])
                }
                AppCompatDelegate.setApplicationLocales(list)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        LiveIsland.stop(this)
        refreshStatus()
    }

    private fun refreshStatus() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        binding.status.setText(if (ok) R.string.audio_ok else R.string.audio_needed)
        binding.grant.isEnabled = !ok
        val listener = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        binding.openListener.alpha = if (listener) 0.5f else 1f
    }

    private fun pick(service: Class<*>) {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        intent.putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, service)
        )
        startActivity(intent)
    }
}
