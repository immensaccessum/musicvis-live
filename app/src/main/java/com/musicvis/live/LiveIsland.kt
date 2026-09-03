package com.musicvis.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.musicvis.live.audio.AudioEngine

object LiveIsland {
    private const val CH = "vis_live"
    private const val ID = 42

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH, context.getString(R.string.island_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.setSound(null, null)
        mgr.createNotificationChannel(ch)
    }

    fun update(context: Context, audio: AudioEngine, visible: Boolean) {
        if (!FeaturePrefs.island(context) || !visible) {
            NotificationManagerCompat.from(context).cancel(ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)
        val pct = (audio.rms * 100f).toInt().coerceIn(0, 100)
        val title = NowPlaying.line ?: context.getString(R.string.island_idle)
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CH)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.island_level, pct))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setProgress(100, pct, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
            if (Build.VERSION.SDK_INT >= 36) {
                val setShort = builder.javaClass.methods.firstOrNull { it.name == "setShortCriticalText" }
                setShort?.invoke(builder, "$pct%")
                val setPromo = builder.javaClass.methods.firstOrNull { it.name == "setRequestPromotedOngoing" }
                setPromo?.invoke(builder, true)
            }
        } catch (_: Throwable) {
        }

        try {
            NotificationManagerCompat.from(context).notify(ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun stop(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }
}
