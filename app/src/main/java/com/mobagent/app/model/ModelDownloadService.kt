package com.mobagent.app.model

import com.mobagent.app.NotificationIntents
import com.mobagent.app.i18n.AppLanguage
import com.mobagent.app.i18n.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** UI shu holatni kuzatib turadi — ekran o'zgarsa yoki qayta ochilsa ham joriy yuklab olish holati yo'qolmaydi. */
sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    data class Running(val modelId: String, val progress: DownloadProgress) : ModelDownloadState()
    /** Foydalanuvchi pauza bosgan: `.part` fayl saqlanib qolgan, "Davom ettirish" shu joydan boshlaydi. */
    data class Paused(val modelId: String, val progress: DownloadProgress) : ModelDownloadState()
    data class Success(val modelId: String, val file: File) : ModelDownloadState()
    data class Error(val modelId: String, val message: String) : ModelDownloadState()
}

/**
 * Model faylini FOREGROUND SERVICE sifatida yuklab oladi — ya'ni bu
 * Activity/Compose ekranining hayot davriga emas, balki alohida Service
 * hayot davriga bog'langan. Foydalanuvchi ChatScreen'ga o'tib ketsa, ilovani
 * fonga tashlab qo'ysa yoki hatto oxirgi vazifalar ro'yxatidan "surib
 * tashlasa" ham (tizim jarayonni o'ldirmaguncha) yuklab olish davom etadi —
 * doimiy bildirishnoma esa foydalanuvchiga buni ko'rsatib turadi.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notificationManager: NotificationManager
    private var downloadJob: Job? = null
    // Har yangi yuklash "avlod" (generation) raqamini oladi. Eski ish bekor qilinganda
    // uning `finally` bloki yangisining servisini to'xtatib yubormasligi uchun
    // barcha holat/bildirishnoma/stopSelf amallari avlod mosligini tekshiradi.
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var pausedGen = -1
    @Volatile private var cancelledGen = -1

    // [FIX 9] Yuklash arzon (Wi-Fi) tarmoqda boshlangan bo'lsa-yu, yo'lda pullik (mobil) tarmoqqa
    // o'tilsa — avtomatik pauza. Foydalanuvchi mobil internetda ataylab boshlagan bo'lsa, aralashmaymiz.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var startedOnMetered = false
    @Volatile private var pausedByMetered = false
    @Volatile private var lastProgress = DownloadProgress("Model", 0, 0)

    override fun onCreate() {
        super.onCreate()
        current = this
        AppLanguage.init(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, tr("Model yuklab olish"), NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            // Bildirishnomadagi "Pauza" tugmasi. Yuklash ketmayotgan bo'lsa — hech narsa qilmaymiz.
            if (downloadJob?.isActive == true) pauseDownload() else stopSelf()
            return START_NOT_STICKY
        }

        val model = intent?.let { extractModel(it) }
        if (model == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val gen = generation.incrementAndGet()
        val startingProgress = DownloadProgress(tr("Boshlanmoqda"), 0, 0)
        lastProgress = startingProgress
        val startingNotification = buildProgressNotification(model, startingProgress)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, startingNotification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        startedOnMetered = isMeteredNetwork(this)
        pausedByMetered = false
        registerMeteredWatcher()

        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                val downloader = ModelDownloader()
                ApiClient.event("download_start", model.id)
                // [FIX 5] boshqa papkada qolgan .part bo'lsa, shu yerga ko'chirib davom ettiramiz.
                adoptPartialDownload(this@ModelDownloadService, model.id)
                val targetDir = modelsDir(this@ModelDownloadService)
                val file = downloader.downloadModel(model, targetDir) { progress ->
                    if (generation.get() != gen || pausedGen == gen) return@downloadModel
                    lastProgress = progress
                    _state.value = ModelDownloadState.Running(model.id, progress)
                    notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(model, progress))
                }
                if (generation.get() == gen) {
                    _state.value = ModelDownloadState.Success(model.id, file)
                    ApiClient.event("download_ok", model.id)
                    notificationManager.notify(NOTIFICATION_ID, buildFinishedNotification(model.name, true, null))
                }
            } catch (e: CancellationException) {
                if (cancelledGen == gen) {
                    // Bekor qilish: yarim fayl ham, meta ham o'chiriladi (yozish oqimi allaqachon yopilgan).
                    deleteModelPartials(this@ModelDownloadService, model.id)
                    if (generation.get() == gen) {
                        _state.value = ModelDownloadState.Idle
                        notificationManager.cancel(NOTIFICATION_ID)
                    }
                } else if (pausedGen == gen) {
                    // Pauza: .part fayl diskda qoladi (FileOutputStream.use yopilgan), davom ettirish shundan boshlanadi.
                    if (generation.get() == gen) {
                        _state.value = ModelDownloadState.Paused(model.id, lastProgress)
                        notificationManager.notify(NOTIFICATION_ID, buildPausedNotification(model, lastProgress))
                    }
                } else {
                    // Yangi yuklash eskisini almashtirdi — .part saqlanadi, holat yangisiniki.
                    throw e
                }
            } catch (e: Exception) {
                if (generation.get() == gen) {
                    _state.value = ModelDownloadState.Error(model.id, e.localizedMessage ?: tr("Yuklab olishda xatolik"))
                    ApiClient.event("download_fail", model.id)
                    notificationManager.notify(NOTIFICATION_ID, buildFinishedNotification(model.name, false, e.localizedMessage))
                }
            } finally {
                // Faqat ENG SO'NGGI yuklash o'z servisini to'xtatadi.
                if (generation.get() == gen) {
                    unregisterMeteredWatcher()
                    ServiceCompat.stopForeground(this@ModelDownloadService, ServiceCompat.STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }

        // START_NOT_STICKY: tizim servisni o'ldirib qayta tiklasa, intent bo'sh keladi va
        // qaysi model yuklanayotgani noma'lum — qayta ishga tushishdan foyda yo'q.
        // .part fayl saqlanadi, foydalanuvchi qo'lda davom ettiradi.
        return START_NOT_STICKY
    }

    /** Yuklashni to'xtatadi, lekin yarim fayl saqlanadi — keyin shu joydan davom ettiriladi. */
    private fun pauseDownload() {
        pausedGen = generation.get()
        downloadJob?.cancel()
    }

    /** Yuklashni butunlay bekor qiladi va yarim faylni o'chiradi. */
    private fun cancelDownload() {
        cancelledGen = generation.get()
        downloadJob?.cancel()
    }

    // Android 15+: dataSync foreground service ~6 soatdan keyin tizim onTimeout() chaqiradi;
    // darhol to'xtamasa ilova ANR/crash bilan yopiladi.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerMeteredWatcher() {
        unregisterMeteredWatcher()
        if (startedOnMetered) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.isMetered() && downloadJob?.isActive == true && !pausedByMetered) {
                    pausedByMetered = true
                    pauseDownload()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            // Ruxsat/tizim cheklovi — kuzatuvsiz davom etamiz.
        }
    }

    private fun unregisterMeteredWatcher() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
        } catch (e: Exception) { /* allaqachon ro'yxatdan o'chgan */ }
    }

    override fun onDestroy() {
        unregisterMeteredWatcher()
        if (current === this) current = null
        scope.cancel()
        super.onDestroy()
    }

    private fun pausePendingIntent(): PendingIntent =
        PendingIntent.getService(
            this, 1,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Servis to'xtab turgan bo'lishi mumkin, shuning uchun foreground service sifatida qayta ishga tushiriladi. */
    private fun resumePendingIntent(model: ModelItem): PendingIntent =
        PendingIntent.getForegroundService(
            this, 2,
            Intent(this, ModelDownloadService::class.java).putExtra(EXTRA_ID, model.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildProgressNotification(model: ModelItem, progress: DownloadProgress): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(tr("%1\$s yuklanmoqda", model.name))
            .setContentText(tr("%1\$s: %2\$s%% (ilovani yopsangiz ham davom etadi)", progress.stage, progress.percent))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress.percent, progress.bytesTotal <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(NotificationIntents.openApp(this))
            .addAction(android.R.drawable.ic_media_pause, tr("Pauza"), pausePendingIntent())
            .build()

    private fun buildPausedNotification(model: ModelItem, progress: DownloadProgress): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(tr("%1\$s pauzada", model.name))
            .setContentText(
                if (pausedByMetered) tr("Mobil internetga o'tildi, yuklash pauzada: %1\$s%%", progress.percent)
                else tr("Pauzada: %1\$s%%", progress.percent)
            )
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setProgress(100, progress.percent, false)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setContentIntent(NotificationIntents.openApp(this))
            .addAction(android.R.drawable.ic_media_play, tr("Davom ettirish"), resumePendingIntent(model))
            .build()

    private fun buildFinishedNotification(modelName: String, success: Boolean, error: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (success) tr("%1\$s tayyor", modelName) else tr("%1\$s yuklanmadi", modelName))
            .setContentText(if (success) tr("Model muvaffaqiyatli yuklab olindi") else (error ?: tr("Xatolik yuz berdi")))
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(NotificationIntents.openApp(this))
            .build()

    private fun extractModel(intent: Intent): ModelItem? {
        val id = intent.getStringExtra(EXTRA_ID) ?: return null
        return ModelCatalog.byId(id)
    }

    companion object {
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_ID = "model_id"
        private const val ACTION_PAUSE = "com.mobagent.app.action.PAUSE_DOWNLOAD"

        @Volatile private var current: ModelDownloadService? = null

        private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
        val state = _state.asStateFlow()

        /** DownloadScreen ochilganda chaqiriladi — yuklab olish darhol fon xizmatida boshlanadi. */
        fun start(context: Context, model: ModelItem) {
            _state.value = ModelDownloadState.Running(model.id, DownloadProgress(tr("Boshlanmoqda"), 0, 0))
            val intent = Intent(context, ModelDownloadService::class.java)
                .putExtra(EXTRA_ID, model.id)
            ContextCompat.startForegroundService(context, intent)
        }

        /** DownloadScreen'dagi "Pauza" tugmasi. Davom ettirish uchun [start] qayta chaqiriladi (.part fayldan davom etadi). */
        fun pause() {
            current?.pauseDownload()
        }

        /**
         * DownloadScreen'dagi "Bekor qilish". Yuklash ketayotgan bo'lsa — to'xtatib
         * yarim faylni o'chiradi; pauzada (servis to'xtagan) bo'lsa — fayllarni
         * to'g'ridan-to'g'ri o'chiradi.
         */
        fun cancel(context: Context, modelId: String) {
            val svc = current
            if (svc != null && svc.downloadJob?.isActive == true) {
                svc.cancelDownload()
            } else {
                deleteModelPartials(context, modelId)
                _state.value = ModelDownloadState.Idle
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(NOTIFICATION_ID)
            }
        }

        /**
         * [FIX 4] Jarayon o'ldirilgandan keyin `_state` xotirada yo'qoladi, lekin `.part` diskda qoladi.
         * Ilova ochilganda shuni "Pauzada" holati sifatida tiklaymiz — do'kon va yuklash ekrani
         * yarim yuklangan modelni ko'rsatadi va "Davom ettirish" shu joydan boshlaydi.
         * Fon tredida chaqiring.
         */
        fun restorePausedIfAny(context: Context) {
            if (_state.value !is ModelDownloadState.Idle) return
            val best = ModelCatalog.models
                .map { it to partialDownloadBytes(context, it.id) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second } ?: return
            val (model, bytes) = best
            val total = model.totalDownloadSizeMb * 1024L * 1024L
            if (_state.value is ModelDownloadState.Idle) {
                _state.value = ModelDownloadState.Paused(model.id, DownloadProgress("Model", bytes, total))
            }
        }

        /** Holat bir marta ishlatilgandan keyin (masalan Success qayta ishlangach) tozalanadi. */
        fun consume() {
            _state.value = ModelDownloadState.Idle
        }
    }
}
