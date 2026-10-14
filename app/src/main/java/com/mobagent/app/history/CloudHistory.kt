package com.mobagent.app.history

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Onlayn suhbatlarning bulutdagi nusxasi: Firestore `users/{uid}/chats/{chatId}`.
 * Bu yo'l faqat egasi o'qiy/yoza oladigan qoidalar bilan himoyalanishi shart
 * (README'dagi "Firestore qoidalari"ga qarang).
 *
 * Eslatma: rasmlar buluta yuklanmaydi (faqat shu telefonda qoladi) — bulutga faqat matn ketadi.
 */
object CloudHistory {
    // Firestore hujjati 1 MiB bilan cheklangan — xabarlar matni shundan oshmasligi uchun.
    private const val MAX_MESSAGES_JSON = 900_000
    private const val FETCH_TIMEOUT_MS = 10_000L

    private val json = Json { ignoreUnknownKeys = true }

    private fun col(uid: String) =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("chats")

    /** Navbatga qo'yadi (oflayn bo'lsa internet kelganda yuboriladi) — natijani kutmaydi. */
    fun upsert(uid: String, s: ChatSession) {
        var msgs = s.messages.map { m ->
            if (m.images.isEmpty()) m else m.copy(images = emptyList(), text = m.text.ifBlank { "📷" })
        }
        var encoded = json.encodeToString(msgs)
        while (encoded.length > MAX_MESSAGES_JSON && msgs.size > 1) {
            msgs = msgs.drop(1)
            encoded = json.encodeToString(msgs)
        }
        col(uid).document(s.id).set(
            hashMapOf<String, Any?>(
                "modelName" to s.modelName,
                "modelId" to s.modelId,
                "createdAt" to s.createdAt,
                "updatedAt" to s.updatedAt,
                "title" to s.title,
                "messages" to encoded
            )
        )
    }

    fun delete(uid: String, id: String) {
        col(uid).document(id).delete()
    }

    /** Serverdan haqiqiy ro'yxat. Internet yo'q bo'lsa istisno tashlaydi (keshdan "bo'sh" deb o'ylamaslik uchun). */
    suspend fun fetchAll(uid: String): List<ChatSession> = withTimeout(FETCH_TIMEOUT_MS) {
        col(uid).get(Source.SERVER).await().documents.mapNotNull { d ->
            try {
                ChatSession(
                    id = d.id,
                    modelName = d.getString("modelName") ?: return@mapNotNull null,
                    modelPath = null,
                    modelId = d.getString("modelId"),
                    createdAt = d.getLong("createdAt") ?: 0L,
                    updatedAt = d.getLong("updatedAt") ?: 0L,
                    messages = json.decodeFromString(d.getString("messages") ?: "[]"),
                    title = d.getString("title")
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Hisob o'chirilganda: foydalanuvchining barcha bulut suhbatlarini o'chiradi. */
    suspend fun deleteAll(uid: String) {
        try {
            withTimeout(FETCH_TIMEOUT_MS) {
                col(uid).get(Source.SERVER).await().documents.forEach { it.reference.delete().await() }
            }
        } catch (_: Exception) {
        }
    }
}
