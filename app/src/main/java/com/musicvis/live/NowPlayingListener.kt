package com.musicvis.live

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification

object NowPlaying {
    @Volatile var line: String? = null
    @Volatile var title: String? = null
    @Volatile var artist: String? = null
}

class NowPlayingListener : NotificationListenerService() {
    override fun onListenerConnected() {
        refresh(activeNotifications)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        refresh(activeNotifications)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        refresh(activeNotifications)
    }

    private fun refresh(list: Array<StatusBarNotification>?) {
        if (list == null) return
        var bestTitle: String? = null
        var bestArtist: String? = null
        for (n in list) {
            val extras = n.notification.extras
            val cat = n.notification.category
            val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
            val media = template.contains("MediaStyle") || cat == Notification.CATEGORY_TRANSPORT
            if (!media) continue
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (!title.isNullOrBlank()) {
                bestTitle = title.trim()
                bestArtist = text?.trim()?.takeIf { it.isNotEmpty() && it != bestTitle }
                if (cat == Notification.CATEGORY_TRANSPORT) break
            }
        }
        NowPlaying.title = bestTitle
        NowPlaying.artist = bestArtist
        NowPlaying.line = when {
            bestTitle == null -> null
            bestArtist != null -> "$bestTitle — $bestArtist"
            else -> bestTitle
        }
    }
}
