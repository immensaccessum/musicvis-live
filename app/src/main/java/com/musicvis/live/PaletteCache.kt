package com.musicvis.live

import android.content.Context

/**
 * Per-engine palette cache: samples the gradient into [size] colors and
 * reloads only when the palette (or the auto color-cycle step) changes.
 * Replaces the palKey/textureKey ritual that every Canvas mode used to copy.
 */
class PaletteCache(private val size: Int) {
    private var key: String? = null

    var colors: IntArray = IntArray(0)
        private set

    /** Refreshes if needed; returns true when the palette was reloaded. */
    fun refresh(context: Context): Boolean {
        val k = HistogramColors.textureKey(context)
        if (k == key && colors.isNotEmpty()) return false
        key = k
        colors = HistogramColors.palette(context, size)
        return true
    }
}
