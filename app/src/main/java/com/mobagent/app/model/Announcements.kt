package com.mobagent.app.model

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** Adminda yozilgan yangilik/e'lon. `level`: info | warn | promo. */
data class Announcement(
    val id: Long,
    val title: String,
    val body: String,
    val level: String,
    val linkUrl: String?
)

/**
 * Bosh sahifadagi e'lon banneri. Server bir vaqtda faqat eng yangi faol e'lonni beradi;
 * foydalanuvchi yopgan e'lonlar id bo'yicha eslab qolinadi va qayta chiqmaydi.
 * Xato/internet yo'q bo'lsa — oldingi holat o'zgarmaydi (jimgina).
 */
object Announcements {
    private const val PREFS = "mobagent_announcement"
    private const val KEY_DISMISSED = "dismissed"
    private const val TIMEOUT_MS = 8_000L
    private const val KEEP = 30
    private val json = Json { ignoreUnknownKeys = true }

    private var fetched by mutableStateOf<Announcement?>(null)
    private var dismissed by mutableStateOf<Set<Long>>(emptySet())
    private var loaded = false

    /** Ko'rsatiladigan e'lon (yopilmagan bo'lsa), aks holda null. */
    val visible: Announcement? get() = fetched?.takeIf { it.id !in dismissed }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DISMISSED, "") ?: ""
        dismissed = raw.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun dismiss(context: Context, a: Announcement) {
        ensureLoaded(context)
        dismissed = dismissed + a.id
        val keep = dismissed.toList().takeLast(KEEP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DISMISSED, keep.joinToString(",")).apply()
    }

    suspend fun refresh(context: Context, langCode: String) {
        if (!ApiConfig.enabled) return
        ensureLoaded(context)
        try {
            val r = withTimeout(TIMEOUT_MS) {
                ApiClient.http.get("${ApiConfig.BASE_URL}/announcement?lang=$langCode")
            }
            if (!r.status.isSuccess()) return
            val root = json.parseToJsonElement(r.bodyAsText()).jsonObject
            fetched = (root["announcement"] as? JsonObject)?.let(::parse)
        } catch (_: Exception) {
        }
    }

    private fun parse(o: JsonObject): Announcement? {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val title = s("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Announcement(
            id = id,
            title = title,
            body = s("body")?.trim().orEmpty(),
            level = s("level") ?: "info",
            linkUrl = s("linkUrl")?.takeIf { it.startsWith("https://") }
        )
    }
}
