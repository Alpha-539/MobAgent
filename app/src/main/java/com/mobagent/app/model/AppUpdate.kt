package com.mobagent.app.model

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobagent.app.i18n.tr
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Ilovani yangilash eslatmasi. Serverdagi `latestCode` ilovaning o'z versionCode'idan katta bo'lsa,
 * bosh sahifada banner chiqadi (bosilsa Google Play ochiladi). Foydalanuvchi yopsa, SHU versiya uchun
 * qayta chiqmaydi (keyingi, yana yangi versiya chiqsa — yana chiqadi). Xato/internet yo'q bo'lsa jim.
 *
 * Banner e'lon bannerining o'zi bilan chiziladi (`Announcement`), shuning uchun alohida UI kerak emas.
 */
object AppUpdate {
    private const val PREFS = "mobagent_app_update"
    private const val KEY_DISMISSED = "dismissed_code"
    private const val TIMEOUT_MS = 8_000L
    private val json = Json { ignoreUnknownKeys = true }

    private var latest by mutableStateOf<Pair<Long, String?>?>(null)
    private var dismissedCode by mutableStateOf(0L)
    private var loaded = false

    /** Ko'rsatiladigan eslatma (yangilash kerak va yopilmagan bo'lsa), aks holda null. */
    fun visible(context: Context): Announcement? {
        val (code, note) = latest ?: return null
        if (code <= dismissedCode || code <= currentCode(context)) return null
        return Announcement(
            id = code,
            title = tr("Ilovaning yangi versiyasi chiqdi"),
            body = note?.takeIf { it.isNotBlank() } ?: tr("Yangilash uchun bosing"),
            level = "promo",
            linkUrl = "https://play.google.com/store/apps/details?id=${context.packageName}"
        )
    }

    fun dismiss(context: Context, a: Announcement) {
        ensureLoaded(context)
        dismissedCode = maxOf(dismissedCode, a.id)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_DISMISSED, dismissedCode).apply()
    }

    suspend fun refresh(context: Context) {
        if (!ApiConfig.enabled) return
        ensureLoaded(context)
        try {
            val r = withTimeout(TIMEOUT_MS) { ApiClient.http.get("${ApiConfig.BASE_URL}/app-update") }
            if (!r.status.isSuccess()) return
            val root = json.parseToJsonElement(r.bodyAsText()).jsonObject
            latest = (root["update"] as? JsonObject)?.let { o ->
                val code = (o["latestCode"] as? JsonPrimitive)?.longOrNull ?: return@let null
                code to (o["note"] as? JsonPrimitive)?.contentOrNull
            }
        } catch (_: Exception) {
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        dismissedCode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DISMISSED, 0L)
    }

    @Suppress("DEPRECATION")
    private fun currentCode(context: Context): Long = runCatching {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    }.getOrDefault(Long.MAX_VALUE) // aniqlab bo'lmasa — eslatma ko'rsatmaymiz
}
