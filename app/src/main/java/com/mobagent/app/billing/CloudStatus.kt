package com.mobagent.app.billing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobagent.app.model.ApiClient
import com.mobagent.app.model.ApiConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Admin paneldan butun pullik rejim o'chirilganmi ("Tez orada")?
 * Xato/internet yo'q bo'lsa — oldingi holat saqlanadi (standart: yoqilgan), shuning uchun
 * tarmoq muammosi pullik rejimni yashirib qo'ymaydi.
 */
object CloudStatus {
    private const val TIMEOUT_MS = 8_000L
    private val json = Json { ignoreUnknownKeys = true }

    var comingSoon by mutableStateOf(false)
        private set

    suspend fun refresh() {
        if (!ApiConfig.enabled) return
        try {
            val r = withTimeout(TIMEOUT_MS) { ApiClient.http.get("${ApiConfig.BASE_URL}/cloud/status") }
            if (!r.status.isSuccess()) return
            val v = json.parseToJsonElement(r.bodyAsText()).jsonObject["comingSoon"]?.jsonPrimitive?.booleanOrNull
            if (v != null) comingSoon = v
        } catch (_: Exception) {
        }
    }
}
