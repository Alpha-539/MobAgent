package com.mobagent.app.model

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.mobagent.app.prompt.ModelFamily
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Worker manzili. Cloudflare'da deploy qilgach, shu yerga o'z manzilingizni yozing
 * (masalan "https://mobagent-api.sizning-nom.workers.dev"). Bo'sh qoldirilsa yoki
 * "<" belgisi bo'lsa — ilova serverga umuman murojaat qilmaydi va faqat
 * o'rnatilgan (ModelCatalog.builtIn) ro'yxatdan foydalanadi.
 */
object ApiConfig {
    const val BASE_URL = "https://mobagent.anonim-dasturchi-uz.workers.dev"

    val enabled: Boolean get() = BASE_URL.startsWith("https://") && !BASE_URL.contains("<")
}

/** Serverdagi katalogni olish, keshlash va ilova ro'yxatiga almashtirish. */
object RemoteCatalog {
    private const val CACHE_FILE = "catalog_cache.json"
    private const val TIMEOUT_MS = 8_000L
    private val json = Json { ignoreUnknownKeys = true }

    /** Ilova ochilganda: oxirgi muvaffaqiyatli katalog (internetsiz ham ishlashi uchun). */
    fun loadCached(context: Context) {
        runCatching {
            val f = File(context.filesDir, CACHE_FILE)
            if (f.exists()) parse(f.readText())?.let { ModelCatalog.remote = it }
        }
    }

    /** Serverdan yangilaydi. Xato bo'lsa jimgina eski ro'yxat qoladi. true = yangilandi. */
    suspend fun refresh(context: Context): Boolean {
        if (!ApiConfig.enabled) return false
        return try {
            val body = withTimeout(TIMEOUT_MS) { ApiClient.http.get("${ApiConfig.BASE_URL}/catalog").bodyAsText() }
            val list = parse(body) ?: return false
            ModelCatalog.remote = list
            withContext(Dispatchers.IO) { File(context.filesDir, CACHE_FILE).writeText(body) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Noto'g'ri yozuvlar tashlab yuboriladi; bo'sh ro'yxat kelsa null (o'rnatilgan ro'yxat saqlanadi). */
    private fun parse(text: String): List<ModelItem>? {
        val arr: JsonArray = runCatching { json.parseToJsonElement(text).jsonObject["models"]?.jsonArray }.getOrNull()
            ?: return null
        val items = arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                ModelItem(
                    id = s("id") ?: return@runCatching null,
                    name = s("name") ?: return@runCatching null,
                    downloadUrl = s("downloadUrl")?.takeIf { it.startsWith("https://") } ?: return@runCatching null,
                    direction = s("direction") ?: "",
                    category = ModelCategory.valueOf(s("category") ?: return@runCatching null),
                    baseSizeMb = o["baseSizeMb"]?.jsonPrimitive?.longOrNull ?: return@runCatching null,
                    baseRamMb = o["baseRamMb"]?.jsonPrimitive?.longOrNull ?: return@runCatching null,
                    minPhoneClass = s("minPhoneClass") ?: "",
                    family = runCatching { ModelFamily.valueOf(s("family") ?: "GENERIC") }.getOrDefault(ModelFamily.GENERIC),
                    paramsBillion = o["paramsBillion"]?.jsonPrimitive?.doubleOrNull ?: return@runCatching null,
                    maxContext = o["maxContext"]?.jsonPrimitive?.intOrNull ?: MAX_CONTEXT,
                    role = s("role")?.let { r -> runCatching { ModelRole.valueOf(r) }.getOrNull() },
                    sha256 = s("sha256")?.lowercase()?.takeIf { it.length == 64 },
                    sizeBytes = o["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    stars = o["stars"]?.jsonPrimitive?.intOrNull ?: 0,
                    systemPrompt = s("systemPrompt")?.trim()?.takeIf { it.isNotEmpty() },
                    agentPrompt = s("agentPrompt")?.trim()?.takeIf { it.isNotEmpty() },
                    badge = s("badge")?.let { b -> runCatching { ModelBadge.valueOf(b) }.getOrNull() }
                )
            }.getOrNull()
        }
        return items.takeIf { it.isNotEmpty() }
    }
}

/** Yulduzcha, hodisa va ma'lumotni o'chirish so'rovlari. Chat matni HECH QACHON yuborilmaydi. */
object ApiClient {
    val http: HttpClient by lazy { HttpClient(Android) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    internal suspend fun token(): String? = try {
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
    } catch (_: Exception) {
        null
    }

    /** Men yulduzcha qo'ygan model id'lari. Xato bo'lsa null. */
    suspend fun myStars(): Set<String>? {
        if (!ApiConfig.enabled) return null
        val t = token() ?: return null
        return try {
            val r = http.get("${ApiConfig.BASE_URL}/me/stars") { header(HttpHeaders.Authorization, "Bearer $t") }
            if (!r.status.isSuccess()) return null
            json.parseToJsonElement(r.bodyAsText()).jsonObject["ids"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
        } catch (_: Exception) {
            null
        }
    }

    /** Yulduzcha qo'yadi/oladi. Muvaffaqiyatli bo'lsa modelning yangi umumiy soni, aks holda null. */
    suspend fun setStar(modelId: String, on: Boolean): Int? {
        if (!ApiConfig.enabled) return null
        val t = token() ?: return null
        return try {
            val url = "${ApiConfig.BASE_URL}/models/$modelId/star"
            val r = if (on) http.put(url) { header(HttpHeaders.Authorization, "Bearer $t") }
            else http.delete(url) { header(HttpHeaders.Authorization, "Bearer $t") }
            if (!r.status.isSuccess()) return null
            json.parseToJsonElement(r.bodyAsText()).jsonObject["stars"]?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            null
        }
    }

    /** Yig'ma statistika (kuchini kutmaydi, xato bo'lsa jim). Token bo'lsa DAU ham sanaladi (server uid'ni saqlamaydi). */
    fun event(metric: String, modelId: String? = null) {
        if (!ApiConfig.enabled) return
        scope.launch {
            try {
                val t = token()
                val body = if (modelId != null) """{"metric":"$metric","modelId":"$modelId"}""" else """{"metric":"$metric"}"""
                http.post("${ApiConfig.BASE_URL}/events") {
                    contentType(ContentType.Application.Json)
                    if (t != null) header(HttpHeaders.Authorization, "Bearer $t")
                    setBody(body)
                }
            } catch (_: Exception) {
            }
        }
    }

    enum class FeedbackResult { Ok, RateLimited, NeedLogin, Failed }

    /**
     * Foydalanuvchi fikrini yuboradi (faqat u yozgan matn; chat matni emas).
     * `kind`: idea | bug | other.
     */
    suspend fun sendFeedback(kind: String, message: String, contact: String, appVersion: String, lang: String): FeedbackResult {
        if (!ApiConfig.enabled) return FeedbackResult.Failed
        val t = token() ?: return FeedbackResult.NeedLogin
        return try {
            val body = buildJsonObject {
                put("kind", kind)
                put("message", message)
                put("contact", contact)
                put("appVersion", appVersion)
                put("lang", lang)
            }.toString()
            val r = withTimeout(10_000L) {
                http.post("${ApiConfig.BASE_URL}/feedback") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $t")
                    setBody(body)
                }
            }
            when {
                r.status.isSuccess() -> FeedbackResult.Ok
                r.status.value == 429 -> FeedbackResult.RateLimited
                r.status.value == 401 || r.status.value == 403 -> FeedbackResult.NeedLogin
                else -> FeedbackResult.Failed
            }
        } catch (_: Exception) {
            FeedbackResult.Failed
        }
    }

    /** Hisob o'chirilishidan OLDIN chaqiriladi: serverdagi yulduzchalar o'chadi. Xato bo'lsa o'chirishni to'xtatmaydi. */
    suspend fun deleteMyData() {
        if (!ApiConfig.enabled) return
        val t = token() ?: return
        try {
            withTimeout(8_000L) {
                http.delete("${ApiConfig.BASE_URL}/me") { header(HttpHeaders.Authorization, "Bearer $t") }
            }
        } catch (_: Exception) {
        }
    }
}
