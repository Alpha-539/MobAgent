package com.mobagent.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Bildirishnoma ustiga bosilganda ilovani ochadigan PendingIntent.
 *
 * Launcher intent (MAIN/LAUNCHER) ishlatiladi: ilova allaqachon fonda ishlayotgan
 * bo'lsa, mavjud holatida oldinga chiqadi (chat yo'qolmaydi), yopilgan bo'lsa
 * yangidan ochiladi.
 */
object NotificationIntents {
    fun openApp(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
