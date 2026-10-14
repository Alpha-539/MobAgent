package com.mobagent.app.linux

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
import com.mobagent.app.NotificationIntents
import com.mobagent.app.i18n.AppLanguage
import com.mobagent.app.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Ubuntu o'rnatilishi davomida jarayonni tirik tutadi (ilova fonga o'tsa ham) va progress
 * bildirishnomasini ko'rsatadi. O'rnatishning o'zi [LinuxInstaller]'da ishlaydi — bu servis
 * faqat uni boshlaydi, holatini kuzatadi va tugagach o'zini to'xtatadi.
 */
class LinuxInstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppLanguage.init(applicationContext)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // Bildirishnomadagi "Bekor qilish": foydalanuvchi buni xohlamaydi — qayta o'zi boshlamaymiz.
            LinuxEnvironment.setAutoOptedOut(this, true)
            LinuxInstaller.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, true) ?: true

        // startForegroundService'dan keyin 5 soniya ichida chaqirilishi SHART.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, progressNotification(tr("Tayyorlanmoqda..."), 0, 0, true),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        LinuxInstaller.start(this, auto)

        watchJob?.cancel()
        watchJob = scope.launch {
            LinuxInstaller.state.collect { st ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                when (st) {
                    is LinuxInstaller.State.Done -> {
                        notifyResult(tr("Linux muhiti tayyor"), tr("Agent endi buyruqlarni Ubuntu ichida bajaradi."))
                        stopSelf()
                    }
                    is LinuxInstaller.State.Failed -> {
                        notifyResult(tr("Linux muhitini o'rnatib bo'lmadi"), st.message.take(200))
                        stopSelf()
                    }
                    is LinuxInstaller.State.Idle -> stopSelf()
                    is LinuxInstaller.State.Checking ->
                        nm.notify(NOTIFICATION_ID, progressNotification(tr("Tekshirilmoqda..."), 0, 0, true))
                    is LinuxInstaller.State.Downloading -> {
                        val text = if (st.total > 0) tr("Yuklanmoqda: %1\$s / %2\$s MB", st.done / 1_000_000, st.total / 1_000_000)
                        else tr("Ulanilmoqda...")
                        val pct = if (st.total > 0) (st.done * 100 / st.total).toInt() else 0
                        nm.notify(NOTIFICATION_ID, progressNotification(text, 100, pct, st.total <= 0))
                    }
                    is LinuxInstaller.State.Verifying ->
                        nm.notify(NOTIFICATION_ID, progressNotification(tr("Tekshirilmoqda (SHA-256)..."), 0, 0, true))
                    is LinuxInstaller.State.Extracting ->
                        nm.notify(NOTIFICATION_ID, progressNotification(tr("Ochilmoqda: %1\$s fayl", st.files), 0, 0, true))
                    is LinuxInstaller.State.Configuring ->
                        nm.notify(NOTIFICATION_ID, progressNotification(tr("Sozlanmoqda..."), 0, 0, true))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // Android 15+: dataSync foreground service ~6 soatdan keyin tizim onTimeout() chaqiradi;
    // darhol to'xtamasa ilova ANR/crash bilan yopiladi.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun progressNotification(text: String, max: Int, progress: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(tr("Linux muhiti o'rnatilmoqda"))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(max, progress, indeterminate)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(NotificationIntents.openApp(this))
            .addAction(
                0, tr("Bekor qilish"),
                PendingIntent.getService(
                    this, 1, Intent(this, LinuxInstallService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun notifyResult(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(NotificationIntents.openApp(this))
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(RESULT_NOTIFICATION_ID, n)
    }

    companion object {
        private const val CHANNEL_ID = "linux_install"
        private const val NOTIFICATION_ID = 79
        private const val RESULT_NOTIFICATION_ID = 80
        private const val EXTRA_AUTO = "auto"
        private const val ACTION_CANCEL = "com.mobagent.app.linux.CANCEL"

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("Linux muhiti"), NotificationManager.IMPORTANCE_LOW)
            )
        }

        /** Faqat ilova ochiq (old planda) paytida chaqiring — Android 12+ fondan foreground service boshlashga ruxsat bermaydi. */
        fun start(context: Context, auto: Boolean) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, LinuxInstallService::class.java).putExtra(EXTRA_AUTO, auto)
                )
            } catch (e: Exception) {
                // Servis boshlab bo'lmadi (masalan, ilova fonda): o'rnatishni servissiz boshlaymiz —
                // ilova ochiq turganda baribir ishlaydi.
                LinuxInstaller.start(context, auto)
            }
        }
    }
}
