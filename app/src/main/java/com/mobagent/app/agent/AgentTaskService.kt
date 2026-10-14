package com.mobagent.app.agent

import com.mobagent.app.AppForeground
import com.mobagent.app.NotificationIntents
import com.mobagent.app.i18n.AppLanguage
import com.mobagent.app.i18n.tr
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Model javob generatsiya qilayotganda yoki ko'p bosqichli buyruqlar zanjiri
 * (AgentOrchestrator.continueAfterTool) davom etayotganda ilova fonga
 * tashlansa (foydalanuvchi Home tugmasini bossa, ekranni o'chirsa va h.k.)
 * ham tizim jarayonni tezda to'xtatib qo'ymasligi uchun.
 *
 * Ilgari bunday himoya umuman yo'q edi — ChatScreen'dagi generatsiya
 * to'g'ridan-to'g'ri Compose'ning `rememberCoroutineScope()` ichida
 * ishlardi, bu esa faqat ilova old planda ekanida ishonchli edi. Bu Service
 * o'zi hech qanday AI ishini bajarmaydi (haqiqiy generatsiya hamon
 * ChatScreen'dagi coroutine'da ketadi) — u faqat "foreground" holatini
 * ko'rsatib, tizimga "foydalanuvchi buni kutmoqda" signalini beradi va
 * doimiy bildirishnoma ko'rsatadi. Vazifa tugagach ChatScreen shu Service'ni
 * to'xtatadi va alohida "tugadi" bildirishnomasini chiqaradi (notifyFinished).
 */
class AgentTaskService : Service() {

    override fun onCreate() {
        super.onCreate()
        AppLanguage.init(applicationContext)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: tr("Javob tayyorlanmoqda...")
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(tr("MobAgent ishlamoqda"))
            .setContentText(label)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(NotificationIntents.openApp(this))
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        return START_NOT_STICKY
    }

    // Android 15+: dataSync foreground service ~6 soatdan keyin tizim onTimeout() chaqiradi;
    // darhol to'xtamasa ilova ANR/crash bilan yopiladi.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "agent_task"
        private const val NOTIFICATION_ID = 77
        private const val FINISHED_NOTIFICATION_ID = 78
        private const val APPROVAL_CHANNEL_ID = "agent_approval"
        private const val APPROVAL_NOTIFICATION_ID = 79
        private const val EXTRA_LABEL = "label"

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("Agent vazifasi"), NotificationManager.IMPORTANCE_LOW)
            )
        }

        /** ChatScreen: yangi so'rov yuborilganda yoki zanjir davom etganda chaqiriladi. */
        fun start(context: Context, label: String = tr("Javob tayyorlanmoqda...")) {
            val intent = Intent(context, AgentTaskService::class.java).putExtra(EXTRA_LABEL, label)
            ContextCompat.startForegroundService(context, intent)
        }

        /** ChatScreen: vazifa tugagach (yakuniy matn, xato yoki to'xtatilgach) chaqiriladi. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AgentTaskService::class.java))
            cancelApproval(context)
        }

        private fun approvalIntent(context: Context, action: String, blockId: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, ToolApprovalReceiver::class.java).setAction(action)
                    .putExtra(ToolApprovalReceiver.EXTRA_BLOCK_ID, blockId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /**
         * Buyruq tasdiqini kutayotganda: ovozli/ko'rinadigan (yuqori muhimlik) alohida bildirishnoma, ichida
         * "Ruxsat berish" va "Rad etish" tugmalari. Qulflangan ekranda buyruq matni yashiriladi va tugmalar chiqmaydi —
         * avval qulfni ochish kerak.
         */
        fun notifyApproval(context: Context, blockId: String, describe: String, step: Int, maxSteps: Int) {
            // Kutayotgan tasdiqni eslab qolamiz: foydalanuvchi ilova ichida bo'lsa bildirishnoma
            // chiqmaydi, lekin ilova fonga o'tsa (onAppBackground) shu ma'lumot bilan chiqariladi.
            val info = PendingApproval(blockId, describe, step, maxSteps)
            pendingApproval = info
            if (AppForeground.isForeground) return
            postApproval(context, info)
        }

        private fun postApproval(context: Context, info: PendingApproval) {
            val blockId = info.blockId
            val describe = info.describe
            val step = info.step
            val maxSteps = info.maxSteps
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(APPROVAL_CHANNEL_ID, tr("Buyruq tasdiqlash"), NotificationManager.IMPORTANCE_HIGH)
            )
            val text = tr("Buyruqni tasdiqlash kerak (%1\$s/%2\$s): %3\$s", step, maxSteps, describe.take(300))
            val publicVersion = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
                .setContentTitle(tr("MobAgent ruxsat so'rayapti"))
                .setContentText(tr("Tasdiqlash kerak"))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .build()
            val notification = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
                .setContentTitle(tr("MobAgent ruxsat so'rayapti"))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setOnlyAlertOnce(true)
                .setContentIntent(NotificationIntents.openApp(context))
                .addAction(NotificationCompat.Action.Builder(
                    0, tr("Ruxsat berish"), approvalIntent(context, ToolApprovalReceiver.ACTION_APPROVE, blockId, 1)
                ).build())
                .addAction(NotificationCompat.Action.Builder(
                    0, tr("Rad etish"), approvalIntent(context, ToolApprovalReceiver.ACTION_DENY, blockId, 2)
                ).build())
                .build()
            nm.notify(APPROVAL_NOTIFICATION_ID, notification)
        }

        private class PendingApproval(val blockId: String, val describe: String, val step: Int, val maxSteps: Int)

        @Volatile
        private var pendingApproval: PendingApproval? = null

        /** Ilova fonga o'tdi: hali tasdiqlanmagan buyruq bo'lsa, endi bildirishnoma kerak. */
        fun onAppBackground(context: Context) {
            pendingApproval?.let { postApproval(context, it) }
        }

        /** Ilova old planga qaytdi: javob/tasdiq endi ekranda ko'rinadi, eski bildirishnomalar keraksiz. */
        fun onAppForeground(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(APPROVAL_NOTIFICATION_ID)
            nm.cancel(FINISHED_NOTIFICATION_ID)
        }

        fun cancelApproval(context: Context) {
            pendingApproval = null
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(APPROVAL_NOTIFICATION_ID)
        }

        /**
         * Vazifa tugagach ko'rsatiladigan, avtomatik yo'qoladigan alohida
         * bildirishnoma — foydalanuvchi ilovadan chiqib ketgan bo'lsa ham
         * javob tayyor bo'lganini bildiradi. Ilova old planda bo'lsa chiqmaydi.
         */
        fun notifyFinished(context: Context, modelName: String, summary: String) {
            // Foydalanuvchi ilova ichida — javob ekranda ko'rinib turibdi, bildirishnoma keraksiz.
            if (AppForeground.isForeground) return
            ensureChannel(context)
            val short = summary.trim().let { if (it.length > 160) it.take(160) + "…" else it }
                .ifBlank { tr("Vazifa tugadi") }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(tr("%1\$s javob berdi", modelName))
                .setContentText(short)
                .setStyle(NotificationCompat.BigTextStyle().bigText(short))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)
                .setContentIntent(NotificationIntents.openApp(context))
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(FINISHED_NOTIFICATION_ID, notification)
        }
    }
}
