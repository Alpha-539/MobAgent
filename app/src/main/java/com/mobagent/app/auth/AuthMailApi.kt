package com.mobagent.app.auth

import com.mobagent.app.model.ApiClient
import com.mobagent.app.model.ApiConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Server (Worker) orqali yuborilgan xat natijasi. */
enum class MailResult {
    Sent,

    /** Juda ko'p urinish — Firebase'ga qaytmaymiz, foydalanuvchiga kutishni aytamiz. */
    RateLimited,

    /** Server sozlanmagan yoki javob bermadi — ilova Firebase'ning o'z xatiga qaytadi. */
    Unavailable,
}

/**
 * Tasdiqlash va parol tiklash xatlarini o'z domenimizdan (Worker orqali) yuboradi.
 * Firebase'ning standart xati ko'pincha spamga tushadi, shuning uchun avval shu yo'l sinaladi.
 */
object AuthMailApi {
    private const val TIMEOUT_MS = 15_000L

    suspend fun sendVerification(lang: String): MailResult {
        if (!ApiConfig.enabled) return MailResult.Unavailable
        val t = ApiClient.token() ?: return MailResult.Unavailable
        return post("/auth/verify-email", buildJsonObject { put("lang", lang) }.toString(), t)
    }

    suspend fun sendReset(email: String, lang: String): MailResult {
        if (!ApiConfig.enabled) return MailResult.Unavailable
        return post("/auth/reset-password", buildJsonObject { put("email", email); put("lang", lang) }.toString(), null)
    }

    private suspend fun post(path: String, body: String, token: String?): MailResult = try {
        val r = withTimeout(TIMEOUT_MS) {
            ApiClient.http.post("${ApiConfig.BASE_URL}$path") {
                contentType(ContentType.Application.Json)
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                setBody(body)
            }
        }
        when {
            r.status.isSuccess() -> MailResult.Sent
            r.status.value == 429 -> MailResult.RateLimited
            else -> MailResult.Unavailable
        }
    } catch (_: Exception) {
        MailResult.Unavailable
    }
}
