package com.musicvis.live

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.musicvis.live.audio.AudioEngine

/**
 * Quick Settings tile: toggles the audio source (system output / microphone)
 * without opening the app. Active state = microphone.
 */
class SourceTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val mic = !FeaturePrefs.micSource(this)
        FeaturePrefs.setMicSource(this, mic)
        AudioEngine.get(this).onSourcePrefChanged()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val mic = FeaturePrefs.micSource(this)
        tile.state = if (mic) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_source)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (mic) R.string.tile_mic else R.string.tile_system)
        }
        tile.updateTile()
    }
}
