package com.mobagent.app.online

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mobagent.app.auth.AuthRepository
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Foydalanuvchining API kalitlarini xavfsiz saqlaydi: qiymat Android Keystore'dagi AES-256-GCM
 * kaliti bilan shifrlanib, SharedPreferences'ga yoziladi (ochiq matn hech qachon diskka tushmaydi).
 *
 * Kalitlar HISOBGA bog'langan: har bir yozuv `<hisob uid>:<xizmat>` nomi bilan saqlanadi, shuning
 * uchun boshqa hisob bilan kirilganda oldingi hisobning kalitlari ko'rinmaydi. Hammasi bitta
 * faylda turadi (backup_rules.xml'dagi istisno nomi o'zgarmasligi uchun).
 *
 * Shifrlash kaliti qurilmadan chiqmaydi, shuning uchun bu fayl zaxiraga kiritilmaydi
 * (backup_rules.xml / data_extraction_rules.xml) — yangi qurilmada baribir ochib bo'lmasdi.
 */
object ApiKeyStore {
    private const val PREFS = "online_api_keys"
    private const val KEY_ALIAS = "mobagent_api_keys_v1"
    private const val IV_SIZE = 12

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Joriy hisob uid'i; tizimga kirilmagan bo'lsa "guest" (ChatHistoryStore bilan bir xil). */
    private fun uid(): String = AuthRepository.user?.uid ?: "guest"

    private fun entry(providerId: String) = "${uid()}:$providerId"

    /** Ko'chirish shu hisob uchun allaqachon bajarilgan (har chaqiruvda SharedPreferences'ni qayta skanerlamaslik uchun). */
    @Volatile private var migratedFor: String? = null

    /**
     * Eski versiyalarda kalitlar hisobsiz (`openai`, `groq`...) saqlangan. Birinchi marta haqiqiy
     * hisob bilan ochilganda ular shu hisobga ko'chiriladi va eski yozuv o'chiriladi.
     */
    @Synchronized
    private fun migrateLegacy(context: Context) {
        val user = AuthRepository.user ?: return
        if (migratedFor == user.uid) return
        migrateLegacyNow(context)
        migratedFor = user.uid
    }

    private fun migrateLegacyNow(context: Context) {
        val p = prefs(context)
        val legacy = p.all.keys.filter { ':' !in it }
        if (legacy.isNotEmpty()) {
            val ed = p.edit()
            for (id in legacy) {
                val v = p.getString(id, null)
                if (v != null && !p.contains(entry(id))) ed.putString(entry(id), v)
                ed.remove(id)
            }
            ed.apply()
        }
        val mp = modelPrefs(context)
        val legacyModels = mp.all.keys.filter { ':' !in it }
        if (legacyModels.isNotEmpty()) {
            val med = mp.edit()
            for (id in legacyModels) {
                val v = mp.getString(id, null)
                if (v != null && !mp.contains(entry(id))) med.putString(entry(id), v)
                med.remove(id)
            }
            med.apply()
        }
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /**
     * Saqlangan kalit; yo'q bo'lsa — null. Yozuv faqat DOIMIY yaroqsiz bo'lsa (Keystore kaliti tiklangan/bekor qilingan,
     * ma'lumot buzilgan) o'chiriladi. Vaqtinchalik Keystore xatosida yozuvga tegilmaydi — keyingi urinishda ochiladi.
     */
    fun get(context: Context, providerId: String): String? {
        migrateLegacy(context)
        val stored = prefs(context).getString(entry(providerId), null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= IV_SIZE) throw IllegalArgumentException("short")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, raw.copyOfRange(0, IV_SIZE))
            )
            String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            if (isPermanent(e)) prefs(context).edit().remove(entry(providerId)).apply()
            null
        }
    }

    private fun isPermanent(e: Exception): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is AEADBadTagException || t is KeyPermanentlyInvalidatedException ||
                t is UnrecoverableKeyException || t is IllegalArgumentException
            ) return true
            t = t.cause
        }
        return false
    }

    /** @return saqlandimi (Keystore ishlamasa false). */
    fun set(context: Context, providerId: String, apiKey: String, sync: Boolean = true): Boolean = try {
        prefs(context).edit().putString(entry(providerId), encryptLocal(apiKey.trim().toByteArray(Charsets.UTF_8))).apply()
        // Sinxronlash yoqilgan bo'lsa, shifrlangan nusxa buluta ham ketadi (CloudKeys).
        if (sync) CloudKeys.pushAsync(context, providerId, apiKey)
        true
    } catch (e: Exception) {
        false
    }

    private fun encryptLocal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun decryptLocal(stored: String): ByteArray {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw.copyOfRange(0, IV_SIZE)))
        return cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE)
    }

    /** Bulut sinxronlash uchun parol-dan yasalgan shifrlash kaliti (shu telefonning Keystore'ida shifrlangan); yoqilmagan bo'lsa null. */
    fun syncKey(context: Context): ByteArray? {
        val stored = prefs(context).getString("${uid()}:__sync", null) ?: return null
        return try { decryptLocal(stored) } catch (e: Exception) { null }
    }

    fun setSyncKey(context: Context, key: ByteArray?) {
        val ed = prefs(context).edit()
        if (key == null) ed.remove("${uid()}:__sync") else ed.putString("${uid()}:__sync", encryptLocal(key))
        ed.apply()
    }

    fun remove(context: Context, providerId: String) {
        CloudKeys.deleteAsync(context, providerId)
        prefs(context).edit().remove(entry(providerId)).apply()
        modelPrefs(context).edit().remove(entry(providerId)).apply()
        customPrefs(context).edit().remove(entry(providerId)).apply()
    }

    /** Hisob o'chirilganda uning barcha kalitlari va tanlangan modellari ham o'chiriladi. */
    @Synchronized
    fun clearAccount(context: Context, uid: String) {
        val prefix = "$uid:"
        for (sp in listOf(prefs(context), modelPrefs(context), customPrefs(context))) {
            val ed = sp.edit()
            sp.all.keys.filter { it.startsWith(prefix) }.forEach { ed.remove(it) }
            ed.apply()
        }
    }

    // Tanlangan model maxfiy emas — alohida (shifrlanmagan) faylda; zaxiraga kirmasligi shart emas.
    private fun modelPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("online_models", Context.MODE_PRIVATE)

    /** Shu xizmat uchun oxirgi tanlangan model (yo'q bo'lsa null). */
    fun getModel(context: Context, providerId: String): String? {
        migrateLegacy(context)
        return modelPrefs(context).getString(entry(providerId), null)
    }

    fun setModel(context: Context, providerId: String, model: String) {
        modelPrefs(context).edit().putString(entry(providerId), model).apply()
    }

    // Qo'lda qo'shilgan model nomlari (maxfiy emas) — xizmat bo'yicha, yangisi tepada, har qatorda bittadan.
    private fun customPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("online_custom_models", Context.MODE_PRIVATE)

    private const val MAX_CUSTOM_MODELS = 50

    fun getCustomModels(context: Context, providerId: String): List<String> =
        customPrefs(context).getString(entry(providerId), null)
            ?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun addCustomModel(context: Context, providerId: String, model: String) {
        val cur = getCustomModels(context, providerId)
        if (model in cur) return
        val next = (listOf(model) + cur).take(MAX_CUSTOM_MODELS)
        customPrefs(context).edit().putString(entry(providerId), next.joinToString("\n")).apply()
    }

    fun removeCustomModel(context: Context, providerId: String, model: String) {
        val next = getCustomModels(context, providerId).filter { it != model }
        val ed = customPrefs(context).edit()
        if (next.isEmpty()) ed.remove(entry(providerId)) else ed.putString(entry(providerId), next.joinToString("\n"))
        ed.apply()
    }

    /** Yozuv bormi (deshifrlamaydi — Compose'da tez chaqirilishi uchun). Yaroqsiz yozuv `get()` da tozalanadi. */
    fun has(context: Context, providerId: String): Boolean {
        migrateLegacy(context)
        return prefs(context).contains(entry(providerId))
    }

    fun savedCount(context: Context): Int = OnlineProviders.all.count { has(context, it.id) }
}
