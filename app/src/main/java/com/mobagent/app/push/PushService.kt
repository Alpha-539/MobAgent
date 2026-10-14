package com.mobagent.app.push

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mobagent.app.NotificationIntents
import com.mobagent.app.R
import com.mobagent.app.i18n.AppLanguage

/**
 * Ilova OCHIQ (oldingi planda) bo'lganda FCM xabarini o'zimiz bildirishnoma qilib ko'rsatamiz
 * (FCM faqat ilova fonda/yopiq bo'lganda o'zi ko'rsatadi).
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Topic obunasi token'ga bog'liq emas (FCM o'zi yangilaydi). Til topic'ini qayta tekshiramiz.
        AppLanguage.init(applicationContext)
        Push.syncTopics(applicationContext, AppLanguage.effective.code)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification
        val title = (n?.title ?: message.data["title"])?.trim().orEmpty()
        val body = (n?.body ?: message.data["body"])?.trim().orEmpty()
        if (title.isEmpty() && body.isEmpty()) return
        if (!Push.hasPermission(this)) return

        AppLanguage.init(applicationContext)
        Push.ensureChannel(this)
        val link = message.data["link"]?.takeIf { it.startsWith("https://") }
        val tap = if (link != null) {
            PendingIntent.getActivity(
                this, 1,
                Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else NotificationIntents.openApp(this)

        val id = message.data["id"]?.toIntOrNull() ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val notification = NotificationCompat.Builder(this, Push.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title.ifEmpty { body })
            .setContentText(body.takeIf { title.isNotEmpty() })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(10_000 + id, notification)
    }
}
