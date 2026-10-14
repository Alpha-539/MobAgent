package com.mobagent.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.mobagent.app.i18n.tr

/**
 * Admin paneldagi e'lonlarni bildirishnoma (push) sifatida olish.
 * Server FCM topic'ga yuboradi: `announce_all` (hamma) va `announce_<til>` (uz/ru/en/ar).
 * Ilova doim `announce_all` + o'z tilining topic'iga obuna; til o'zgarsa eskisidan chiqadi.
 */
object Push {
    const val CHANNEL_ID = "announcements"
    private const val PREFS = "mobagent_push"
    private const val KEY_LANG = "topic_lang"
    private const val KEY_ASKED = "perm_asked"
    private val LANGS = listOf("uz", "ru", "en", "ar")

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr("Yangiliklar va e'lonlar"), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** Joriy til topic'iga obuna bo'ladi (bir xil til bo'lsa qayta chaqirmaydi). */
    fun syncTopics(context: Context, langCode: String) {
        if (langCode !in LANGS) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = prefs.getString(KEY_LANG, null)
        if (prev == langCode) return
        val fm = try { FirebaseMessaging.getInstance() } catch (_: Exception) { return }
        fm.subscribeToTopic("announce_all")
        fm.subscribeToTopic("announce_$langCode").addOnSuccessListener {
            if (prev != null && prev != langCode && prev in LANGS) fm.unsubscribeFromTopic("announce_$prev")
            prefs.edit().putString(KEY_LANG, langCode).apply()
        }
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android 13+: ruxsat hali so'ralmagan bo'lsa true (faqat bir marta so'raymiz, bezovta qilmaymiz). */
    fun shouldAskPermission(context: Context): Boolean {
        if (hasPermission(context)) return false
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
    }

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }
}
