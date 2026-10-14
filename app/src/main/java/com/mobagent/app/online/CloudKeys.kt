package com.mobagent.app.online

import android.content.Context
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import com.mobagent.app.auth.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * API kalitlarni hisob bo'yicha telefonlar o'rtasida AVTOMATIK sinxronlash (parolsiz): Firestore
 * `users/{uid}/keys/{xizmat}`. Foydalanuvchi hech narsa kiritmaydi — hisobga kirilishi bilan kalitlar
 * boshqa telefondan tushadi.
 *
 * Kalitlar buluta AES-256-GCM bilan shifrlanib ketadi; shifrlash kaliti hisobga xos tasodifiy kalit bo'lib,
 * `_meta` hujjatida turadi. Parol yo'qligi sababli bu faqat himoya qatlami: kalitlarni Firestore qoidalari
 * (faqat egasi o'qiydi) va Google'ning bazani shifrlashi himoya qiladi, baza ma'muriga esa ular ochiladi.
 */
object CloudKeys {
    private const val META = "_meta"
    private const val IV_SIZE = 12
    private const val TIMEOUT_MS = 12_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun uid(): String? = AuthRepository.user?.uid

    private fun col(uid: String) =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("keys")

    private fun seal(key: ByteArray, plain: ByteArray): String {
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    private fun open(key: ByteArray, b64: String): ByteArray {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, IV_SIZE)))
        return c.doFinal(raw, IV_SIZE, raw.size - IV_SIZE)
    }

    /**
     * To'liq sinxronlash (idempotent): hisobning bulut kalitini oladi (yo'q bo'lsa yaratadi), telefondagi
     * kalitlarni buluta yuklaydi, telefonda yo'q kalitlarni bulutdan tushiradi.
     * @return telefonga yangi tushgan kalitlar soni; internet/Firestore xatosida -1 (jimgina, keyingi safar qayta uriniladi).
     */
    suspend fun sync(context: Context): Int {
        val u = uid() ?: return -1
        return try {
            withContext(Dispatchers.Default) {
                val metaRef = col(u).document(META)
                // Ikki telefon bir vaqtda birinchi marta kirsa ham bitta kalit yaratiladi (tranzaksiya).
                val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
                withTimeout(TIMEOUT_MS) {
                    FirebaseFirestore.getInstance().runTransaction(Transaction.Function<Void?> { tx ->
                        if (!tx.get(metaRef).exists()) {
                            tx.set(metaRef, hashMapOf("k" to Base64.encodeToString(fresh, Base64.NO_WRAP)))
                        }
                        null
                    }).await()
                }
                val meta = withTimeout(TIMEOUT_MS) { metaRef.get(Source.SERVER).await() }
                val key = Base64.decode(meta.getString("k") ?: return@withContext -1, Base64.NO_WRAP)
                ApiKeyStore.setSyncKey(context, key)

                val docs = withTimeout(TIMEOUT_MS) { col(u).get(Source.SERVER).await().documents }
                val remoteIds = docs.map { it.id }.toSet()
                // Telefonda bor, bulutda yo'q -> yuklaymiz.
                for (p in OnlineProviders.all) {
                    if (p.id in remoteIds) continue
                    val k = ApiKeyStore.get(context, p.id) ?: continue
                    withTimeout(TIMEOUT_MS) {
                        col(u).document(p.id).set(hashMapOf("c" to seal(key, k.toByteArray(Charsets.UTF_8)))).await()
                    }
                }
                // Bulutda bor, telefonda yo'q -> tushiramiz (telefondagi mavjud kalitga tegmaymiz).
                var n = 0
                for (d in docs) {
                    if (d.id == META || OnlineProviders.byId(d.id) == null || ApiKeyStore.has(context, d.id)) continue
                    val plain = try { String(open(key, d.getString("c").orEmpty()), Charsets.UTF_8) } catch (e: Exception) { continue }
                    if (ApiKeyStore.set(context, d.id, plain, sync = false)) n++
                }
                n
            }
        } catch (e: Exception) {
            -1
        }
    }

    /** Kalit saqlanganda (ApiKeyStore.set) fonda buluta yuboradi. */
    fun pushAsync(context: Context, providerId: String, apiKey: String) {
        val u = uid() ?: return
        val key = ApiKeyStore.syncKey(context)
        scope.launch {
            try {
                // Bulut kaliti hali olinmagan bo'lsa (birinchi kirish) — to'liq sinxronlash telefondagi kalitni o'zi yuklaydi.
                if (key == null) { sync(context); return@launch }
                col(u).document(providerId).set(hashMapOf("c" to seal(key, apiKey.trim().toByteArray(Charsets.UTF_8))))
            } catch (_: Exception) { }
        }
    }

    fun deleteAsync(context: Context, providerId: String) {
        val u = uid() ?: return
        scope.launch { try { col(u).document(providerId).delete() } catch (_: Exception) { } }
    }

    /** Hisob o'chirilganda: buluttagi barcha kalitlarni o'chiradi. */
    suspend fun deleteAll(context: Context) {
        val u = uid() ?: return
        try {
            withTimeout(TIMEOUT_MS) { col(u).get(Source.SERVER).await().documents.forEach { it.reference.delete().await() } }
        } catch (_: Exception) { }
        ApiKeyStore.setSyncKey(context, null)
    }
}
