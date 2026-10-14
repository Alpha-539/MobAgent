package com.mobagent.app.history

import android.content.Context
import com.mobagent.app.auth.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File
import java.util.UUID

/**
 * Har bir suhbatni ilovaning shaxsiy papkasida alohida JSON fayl sifatida saqlaydi:
 * `files/chat_history/<hisob uid>/<id>.json`. Tarix HISOBGA bog'langan — boshqa hisob bilan
 * kirilsa, uning o'z tarixi ko'rinadi.
 *
 * Onlayn suhbatlar (modelPath == null) qo'shimcha ravishda Firestore'ga ham yoziladi
 * ([CloudHistory]) va [syncFromCloud] orqali boshqa qurilmadan / qayta o'rnatishdan keyin tiklanadi.
 * Oflayn (lokal model) suhbatlar faqat telefonda qoladi.
 */
class ChatHistoryStore(context: Context) {

    private val root = File(context.filesDir, "chat_history").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val uid: String get() = AuthRepository.user?.uid ?: "guest"
    private val cloudUid: String? get() = AuthRepository.user?.uid

    /** Joriy hisobning papkasi. Hisob-bog'lash qo'shilishidan oldingi (ildizdagi) suhbatlar birinchi ochilishda shu yerga ko'chiriladi. */
    private fun dir(): File {
        val d = File(root, uid).apply { mkdirs() }
        val marker = File(root, ".legacy_migrated")
        if (!marker.exists()) {
            root.listFiles { f -> f.isFile && f.extension == "json" }?.forEach { f ->
                runCatching { f.renameTo(File(d, f.name)) }
            }
            runCatching { marker.writeText("1") }
        }
        return d
    }

    private fun fileFor(id: String) = File(dir(), "$id.json")

    // Bulutga yuborilgan (yoki undan kelgan) suhbat id'lari — boshqa qurilmada o'chirilganini aniqlash uchun.
    private fun syncedFile() = File(dir(), ".synced")
    private fun readSynced(): MutableSet<String> =
        runCatching { syncedFile().readLines().filter { it.isNotBlank() }.toMutableSet() }.getOrDefault(mutableSetOf())
    private fun writeSynced(ids: Set<String>) {
        runCatching { syncedFile().writeText(ids.joinToString("\n")) }
    }

    private fun isOnline(s: ChatSession) = s.modelPath == null

    suspend fun createSession(modelName: String, modelPath: String?, modelId: String? = null): ChatSession =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            ChatSession(
                id = UUID.randomUUID().toString(),
                modelName = modelName,
                modelPath = modelPath,
                modelId = modelId,
                createdAt = now,
                updatedAt = now,
                messages = emptyList()
            )
        }

    suspend fun save(session: ChatSession) = withContext(Dispatchers.IO) {
        try {
            fileFor(session.id).writeText(json.encodeToString(session))
        } catch (e: Exception) {
            // Diskka yozib bo'lmasa ham chat davom etaversin — faqat tarix saqlanmaydi.
        }
        pushToCloud(session)
    }

    private fun pushToCloud(session: ChatSession) {
        val u = cloudUid ?: return
        if (!isOnline(session) || session.messages.isEmpty()) return
        try {
            CloudHistory.upsert(u, session)
            writeSynced(readSynced().apply { add(session.id) })
        } catch (e: Exception) {
        }
    }

    suspend fun load(id: String): ChatSession? = withContext(Dispatchers.IO) {
        try {
            val f = fileFor(id)
            if (!f.exists()) return@withContext null
            json.decodeFromString<ChatSession>(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) {
        try {
            val f = fileFor(id)
            if (!f.exists()) return@withContext
            val s = json.decodeFromString<ChatSession>(f.readText())
            val renamed = s.copy(title = title.trim().ifBlank { null })
            f.writeText(json.encodeToString(renamed))
            pushToCloud(renamed)
        } catch (e: Exception) {
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val f = fileFor(id)
        // Suhbatdagi rasm fayllari ham tozalanadi.
        runCatching {
            if (f.exists()) json.decodeFromString<ChatSession>(f.readText())
                .messages.flatMap { it.images }.forEach { ImageStore.delete(it) }
        }
        f.delete()
        val u = cloudUid
        if (u != null) {
            val synced = readSynced()
            if (synced.remove(id)) {
                runCatching { CloudHistory.delete(u, id) }
                writeSynced(synced)
            }
        }
    }

    /**
     * Bulutdagi onlayn suhbatlarni telefon bilan moslaydi: yangilarini yuklab oladi, bu telefonda
     * hali bulutga chiqmaganlarini yuboradi, boshqa qurilmada o'chirilganlarini bu yerdan ham o'chiradi.
     * Internet yo'q bo'lsa jim o'tadi (lokal tarix o'zgarishsiz qoladi).
     */
    suspend fun syncFromCloud() = withContext(Dispatchers.IO) {
        val u = cloudUid ?: return@withContext
        val remote = try {
            CloudHistory.fetchAll(u)
        } catch (e: Exception) {
            return@withContext
        }
        val remoteIds = remote.map { it.id }.toSet()
        val synced = readSynced()
        val d = dir()

        for (r in remote) {
            val f = File(d, "${r.id}.json")
            val local = runCatching { json.decodeFromString<ChatSession>(f.readText()) }.getOrNull()
            if (local == null || r.updatedAt > local.updatedAt) {
                // Bulut nusxasida rasm yo'q — shu qurilmada bor rasmlarni joyida saqlab qolamiz.
                val merged = if (local == null) r else r.copy(
                    messages = r.messages.mapIndexed { i, m ->
                        local.messages.getOrNull(i)?.takeIf { it.fromUser == m.fromUser && it.images.isNotEmpty() } ?: m
                    }
                )
                runCatching { f.writeText(json.encodeToString(merged)) }
            }
            synced.add(r.id)
        }

        // Faqat onlayn suhbatlar bulut bilan sinxronlanadi.
        val locals = d.listFiles { f -> f.isFile && f.extension == "json" }?.mapNotNull { f ->
            runCatching { json.decodeFromString<ChatSession>(f.readText()) }.getOrNull()
        }.orEmpty().filter { isOnline(it) }

        for (l in locals) {
            if (l.id in remoteIds) continue
            if (l.id in synced) {
                // Ilgari yuborilgan, endi bulutda yo'q — boshqa qurilmada o'chirilgan.
                File(d, "${l.id}.json").delete()
                l.messages.flatMap { it.images }.forEach { ImageStore.delete(it) }
                synced.remove(l.id)
            } else if (l.messages.isNotEmpty()) {
                runCatching { CloudHistory.upsert(u, l); synced.add(l.id) }
            }
        }
        writeSynced(synced)
    }

    /**
     * Bitta agent/modelga tegishli suhbatlar (oxirgi yangilangani birinchi).
     * Oflayn agentlar uchun [modelId] bo'yicha, "Onlayn AI" uchun (modelId yo'q)
     * [modelName] bo'yicha filtrlanadi — ilgari barcha modellarning suhbatlari
     * bitta umumiy ro'yxatda aralashib ketardi.
     */
    suspend fun listSessions(modelId: String? = null, modelName: String? = null): List<ChatSession> = withContext(Dispatchers.IO) {
        dir().listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { f ->
                try {
                    json.decodeFromString<ChatSession>(f.readText())
                } catch (e: Exception) {
                    null
                }
            }
            ?.filter { session ->
                when {
                    modelId != null -> session.modelId == modelId
                    modelName != null -> session.modelId == null && session.modelName == modelName
                    else -> true
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }
}
