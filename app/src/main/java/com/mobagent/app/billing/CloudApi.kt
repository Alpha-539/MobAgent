package com.mobagent.app.billing

import com.mobagent.app.i18n.tr
import com.mobagent.app.model.ApiClient
import com.mobagent.app.model.ApiConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class CloudModelInfo(val id: String, val name: String, val tier: String, val locked: Boolean)

/** Dollar miqdori ko'rsatilmaydi: faqat foiz (0..1) va tiklanish vaqti. */
data class CloudPlan(
    val planId: String?,
    val validUntil: Long,
    val windowUsed: Double,
    val weekUsed: Double,
    val monthUsed: Double,
    val windowResetsAt: Long,
    val weekResetsAt: Long,
    val monthResetsAt: Long,
    val models: List<CloudModelInfo>,
    /** Qolgan credits ("kredit" birligida). Tarif bo'lmasa ham ishlatiladi. */
    val credits: Long = 0L
)

/** Tarif sahifasiga o'tib kelguncha yozilgan, hali yuborilmagan xabar (chat qayta ochilganda tiklanadi). */
object PendingPrompt {
    @Volatile private var text: String = ""
    fun put(s: String) { text = s }
    fun take(): String = text.also { text = "" }
}

object CloudApi {
    const val PROVIDER_ID = "mobagent"
    /** "MobAgent agents" bosilganda ochiladigan standart model (worker/src/plans.ts dagi id bilan bir xil). */
    const val DEFAULT_MODEL_ID = "claude-haiku-4-5"
    const val DEFAULT_MODEL_NAME = "Claude Haiku 4.5"
    private val json = Json { ignoreUnknownKeys = true }

    private fun errorOf(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** Joriy tarif va foydalanish. Xato bo'lsa null. */
    suspend fun plan(): CloudPlan? {
        if (!ApiConfig.enabled) return null
        val t = ApiClient.token() ?: return null
        return try {
            val r = ApiClient.http.get("${ApiConfig.BASE_URL}/me/plan") { header(HttpHeaders.Authorization, "Bearer $t") }
            if (!r.status.isSuccess()) return null
            val o: JsonObject = json.parseToJsonElement(r.bodyAsText()).jsonObject
            fun d(k: String) = o[k]?.jsonPrimitive?.doubleOrNull ?: 0.0
            fun l(k: String) = o[k]?.jsonPrimitive?.longOrNull ?: 0L
            CloudPlan(
                planId = o["planId"]?.jsonPrimitive?.contentOrNull,
                validUntil = l("validUntil"),
                credits = l("credits"),
                windowUsed = d("windowUsed"), weekUsed = d("weekUsed"), monthUsed = d("monthUsed"),
                windowResetsAt = l("windowResetsAt"), weekResetsAt = l("weekResetsAt"), monthResetsAt = l("monthResetsAt"),
                models = o["models"]?.jsonArray?.mapNotNull { e ->
                    val m = e.jsonObject
                    CloudModelInfo(
                        id = m["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        name = m["name"]?.jsonPrimitive?.contentOrNull ?: m["id"]!!.jsonPrimitive.content,
                        tier = m["tier"]?.jsonPrimitive?.contentOrNull ?: "medium",
                        locked = m["locked"]?.jsonPrimitive?.booleanOrNull ?: false
                    )
                }.orEmpty()
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Xarid tokenini serverga yuboradi (server Google Play bilan tekshirib, tasdiqlaydi). null = muvaffaqiyatli, aks holda xabar. */
    suspend fun verify(productId: String, purchaseToken: String): String? {
        if (!ApiConfig.enabled) return tr("Server sozlanmagan")
        val t = ApiClient.token() ?: return tr("Avval hisobga kiring")
        return try {
            val r = ApiClient.http.post("${ApiConfig.BASE_URL}/billing/verify") {
                header(HttpHeaders.Authorization, "Bearer $t")
                contentType(ContentType.Application.Json)
                setBody("""{"productId":"$productId","purchaseToken":"$purchaseToken"}""")
            }
            if (r.status.isSuccess()) null else (errorOf(r.bodyAsText()) ?: tr("Obunani tasdiqlab bo'lmadi"))
        } catch (_: Exception) {
            tr("Internet aloqasi yo'q. Obuna keyinroq avtomatik tasdiqlanadi.")
        }
    }

    /** Credits xaridini serverga yuboradi (server Google Play'da tekshirib, balansga qo'shadi). null = muvaffaqiyatli, aks holda xabar. */
    suspend fun verifyCredits(productId: String, purchaseToken: String): String? {
        if (!ApiConfig.enabled) return tr("Server sozlanmagan")
        val t = ApiClient.token() ?: return tr("Avval hisobga kiring")
        return try {
            val r = ApiClient.http.post("${ApiConfig.BASE_URL}/billing/credits") {
                header(HttpHeaders.Authorization, "Bearer $t")
                contentType(ContentType.Application.Json)
                setBody("""{"productId":"$productId","purchaseToken":"$purchaseToken"}""")
            }
            if (r.status.isSuccess()) null else (errorOf(r.bodyAsText()) ?: tr("Credits'ni tasdiqlab bo'lmadi"))
        } catch (_: Exception) {
            tr("Internet aloqasi yo'q. Credits keyinroq avtomatik qo'shiladi.")
        }
    }
}
