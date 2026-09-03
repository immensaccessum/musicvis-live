package com.musicvis.live

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.RemoteViews
import com.musicvis.live.audio.AudioEngine
import kotlin.math.abs

class HistogramWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, mgr, it) }
    }

    companion object {
        fun push(context: Context, audio: AudioEngine) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, HistogramWidget::class.java))
            if (ids.isEmpty()) return
            val bmp = render(audio)
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_histogram)
                views.setImageViewBitmap(R.id.bars, bmp)
                val line = NowPlaying.line ?: context.getString(R.string.widget_title)
                views.setTextViewText(R.id.title, line)
                mgr.updateAppWidget(id, views)
            }
        }

        private fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_histogram)
            views.setTextViewText(R.id.title, NowPlaying.line ?: context.getString(R.string.widget_title))
            mgr.updateAppWidget(id, views)
        }

        private fun render(audio: AudioEngine): Bitmap {
            val w = 600
            val h = 160
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.parseColor("#E6101018"))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(80, 80, 255)
            val pcm = audio.pcm
            val n = 48
            val gap = 2f
            val bw = (w - gap * (n + 1)) / n
            val cy = h / 2f
            for (i in 0 until n) {
                val amp = abs(pcm[i * pcm.size / n] / 128f).coerceIn(0.04f, 1f)
                val bh = amp * (h * 0.45f)
                val x = gap + i * (bw + gap)
                c.drawRoundRect(x, cy - bh, x + bw, cy + bh, 4f, 4f, paint)
            }
            return bmp
        }
    }
}
