package com.mobagent.app.online

import com.mobagent.app.history.ImageStore
import com.mobagent.app.prompt.toEnglishForModel
import com.mobagent.app.history.StoredMessage
import com.mobagent.app.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Kalit tekshiruvi natijasi. Matnlar UI qatlamida (tr bilan) tuziladi. */
sealed class KeyVerdict {
    /** Kalit xizmat tomonidan qabul qilindi. [note] — kalit to'g'ri, lekin hozir so'rov o'tmasligi mumkin. */
    data class Valid(
        val models: List<String>,
        val defaultModel: String?,
        val note: KeyNote? = null
    ) : KeyVerdict()

    /** Xizmat kalitni rad etdi (401/403, Gemini'da 400). [detail] — xizmatning o'z xabari. */
    data class Invalid(val detail: String?) : KeyVerdict()

    /** Internet yo'q yoki xizmatga yetib bo'lmadi — kalit haqida hech narsa deya olmaymiz. */
    object NoNetwork : KeyVerdict()

    /** Kutilmagan javob (masalan 5xx) — kalit haqida hech narsa deya olmaymiz. */
    data class Unknown(val code: Int, val detail: String?) : KeyVerdict()
}

enum class KeyNote { RATE_LIMITED, NO_CREDIT }

/** Oqimli javob natijasi. [truncated] — model chiqish limitiga yetib, javob uzilib qolgan (finish_reason=length / stop_reason=max_tokens). */
class StreamResult(val text: String, val truncated: Boolean = false, val sources: List<WebSource> = emptyList())

/** Veb-qidiruvli javobdagi manba (Perplexity Agent API: `url_citation` annotatsiyalari / `search_results`). */
class WebSource(val title: String, val url: String)

/** Qo'lda kiritilgan model nomi xizmatda bormi: topilmasa (NOT_FOUND) qo'shilmaydi; tarmoq xatosida (UNKNOWN) qo'shiladi. */
enum class ModelCheck { EXISTS, NOT_FOUND, UNKNOWN }

/** Chat paytidagi xato; [message] allaqachon foydalanuvchi tilida. */
class OnlineException(message: String) : IOException(message)

/** Tanlangan xizmat + kalit + model. */
class OnlineSession(val provider: OnlineProvider, val apiKey: String, val model: String)

/**
 * Onlayn xizmatlar bilan ishlash: kalitni tekshirish, model ro'yxatini olish va oqimli (streaming) chat.
 * Qo'shimcha kutubxona kerak emas — `HttpURLConnection` + kotlinx.serialization.
 *
 * Har xizmatning autentifikatsiyasi va tekshiruv so'rovi [OnlineProvider] da hujjatlarga mos ko'rsatilgan.
 */
object OnlineApi {
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val VERIFY_READ_TIMEOUT_MS = 20_000
    private const val STREAM_READ_TIMEOUT_MS = 120_000
    private const val MAX_MODELS = 80
    private const val MAX_SOURCES = 6
    // Modelning bitta javobdagi chiqish limiti (token): Copilot/Claude kabi katta — uzun kod uzilib qolmasin.
    // Xizmat/model bundan kichik limitni qo'llasa (400), narvon bo'ylab avtomatik pasaytiriladi va
    // shu model uchun eslab qolinadi (learnedCap).
    private const val ONLINE_MAX_OUTPUT_TOKENS = 32768
    private val CAP_LADDER = listOf(32768, 32000, 16384, 8192, 4096)
    private val learnedCap = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** [fromContext] — limit xizmatning chiqish-limiti xatosidan emas, kontekst oynasidagi qolgan joydan hisoblangan (eslab qolinmaydi). */
    private class TokenLimitRetry(val next: Int, val fromContext: Boolean = false) : RuntimeException()

    /** Suhbat tarixining o'zi modelning kontekst oynasiga sig'madi: tarixning eski qismi tashlab, qayta uriniladi. */
    private class ContextRetry : RuntimeException()
    /** 429 (so'rovlar limiti): xizmat ko'rsatgan kutish vaqtidan so'ng avtomatik qayta uriniladi. */
    private class RateLimitRetry(val waitMs: Long, val message2: String) : RuntimeException()

    private const val MAX_HISTORY_TRIMS = 3
    // 429 bo'lganda qayta urinishlar orasidagi kutish (ms): jami 4 marta.
    private val RATE_LIMIT_BACKOFF_MS = longArrayOf(3_000L, 8_000L, 15_000L, 25_000L)

    private fun startCap(provider: OnlineProvider, model: String): Int =
        learnedCap["${provider.id}:$model"]
            ?: if (provider.style == ApiStyle.ANTHROPIC) 32000 else ONLINE_MAX_OUTPUT_TOKENS

    private fun looksLikeTokenLimit(body: String): Boolean {
        val b = body.lowercase()
        return "max_tokens" in b || "max_completion_tokens" in b || "max_output_tokens" in b ||
            "completion tokens" in b || "output tokens" in b
    }

    /** Kontekst oynasi to'lganini bildiruvchi xato (chiqish limiti xatosidan farqli). */
    private fun looksLikeContextOverflow(body: String): Boolean {
        val b = body.lowercase()
        return "context length" in b || "context_length" in b || "context window" in b ||
            "reduce the length" in b || "prompt is too long" in b || "input is too long" in b ||
            "too many tokens" in b
    }

    private val CTX_MAX = Regex("(?:maximum|max)\\s+context\\s+(?:length|window|size)[^0-9]{0,40}(\\d{3,7})", RegexOption.IGNORE_CASE)
    private val CTX_PROMPT = Regex("(\\d{1,7})\\s+in\\s+the\\s+(?:messages|prompt|input)", RegexOption.IGNORE_CASE)

    /**
     * Kontekst to'lgan: xato matnidan "kontekst N, so'rov matni P" o'qilsa va chiqish uchun >= 1024 joy qolsa —
     * chiqish limiti shunga tushiriladi; matnning o'zi sig'masa (yoki raqam yo'q) — tarix qisqartiriladi.
     * Raqamsiz xabarda chiqish limiti juda katta bo'lsa (> 8192), avval o'sha pasaytiriladi.
     */
    private fun onContextOverflow(cap: Int, body: String): Nothing {
        val max = CTX_MAX.find(body)?.groupValues?.get(1)?.toIntOrNull()
        val prompt = CTX_PROMPT.find(body)?.groupValues?.get(1)?.toIntOrNull()
        if (max != null && prompt != null) {
            val room = (max - prompt) / 256 * 256
            if (room >= 1024 && room < cap) throw TokenLimitRetry(room, fromContext = true)
            throw ContextRetry()
        }
        if (cap > 8192) throw TokenLimitRetry(CAP_LADDER.first { it <= 16384 && it < cap }, fromContext = true)
        throw ContextRetry()
    }

    /**
     * Xato matnida ruxsat etilgan limit ko'rsatilgan bo'lsa (masalan "max_tokens: 32000 > 8192"), eng kattasini (lekin
     * yuborilgan [cap]dan kichigini) oladi — narvon bo'ylab bir necha marta urinmasdan; aks holda narvondagi keyingi pastini.
     */
    private fun nextCap(cap: Int, body: String): Int? {
        val hinted = Regex("\\d{4,6}").findAll(body).mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1024 until cap }.maxOrNull()
        if (hinted != null) return hinted
        return CAP_LADDER.firstOrNull { it < cap }
    }
    // OpenRouter katalogi 400+ model: 80 tada kesilsa mashhur modellar (openai/, google/, x-ai/...) ro'yxatga tushmay qolardi.
    private const val MAX_MODELS_LARGE_CATALOG = 600

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var activeConn: HttpURLConnection? = null
    @Volatile private var cancelled = false

    private class Resp(val code: Int, val body: String)

    // ───────────────────────── Kalitni tekshirish ─────────────────────────

    suspend fun verify(provider: OnlineProvider, rawKey: String): KeyVerdict = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        try {
            val resp = when (provider.check) {
                KeyCheck.MODELS -> get(provider, key, modelsUrl(provider))
                KeyCheck.OPENROUTER_KEY -> get(provider, key, provider.baseUrl + "/key")
                KeyCheck.AGENT_PING -> post(
                    provider, key, provider.baseUrl + "/v1/agent",
                    agentPingBody(provider.pingModel ?: "openai/gpt-6-luna"),
                    VERIFY_READ_TIMEOUT_MS
                )
            }
            when (resp.code) {
                in 200..299 -> {
                    val models = when (provider.check) {
                        KeyCheck.MODELS -> parseModels(provider, resp.body)
                        // OpenRouter'da kalit /key bilan tasdiqlanadi, ro'yxat esa ochiq /models'dan olinadi.
                        KeyCheck.OPENROUTER_KEY -> runCatching {
                            parseModels(provider, get(provider, key, modelsUrl(provider)).body)
                        }.getOrDefault(emptyList())
                        // Perplexity: kalit /v1/agent bilan tasdiqlanadi, ro'yxat (presetlar + modellar) alohida olinadi.
                        KeyCheck.AGENT_PING -> agentModels(provider)
                    }.ifEmpty { fallbackModels(provider) }
                    KeyVerdict.Valid(models, pickDefault(provider, models))
                }
                // xAI: yangi/kreditsiz jamoalarda `/models` 403 qaytarishi mumkin, chat esa ishlaydi — qayta tekshiramiz.
                403 -> if (provider.id == "xai") xaiChatFallback(provider, key, resp)
                else KeyVerdict.Invalid(errorMessage(resp.body))
                401 -> KeyVerdict.Invalid(errorMessage(resp.body))
                // Gemini noto'g'ri kalitga 401 emas, 400 "API key not valid" qaytaradi.
                // Perplexity: sinov modeli yo'q/bekor qilingan bo'lsa 400/404 — autentifikatsiya o'tgan, kalit to'g'ri.
                400, 404 -> if (looksLikeBadKey(resp.body)) KeyVerdict.Invalid(errorMessage(resp.body))
                else if (provider.check == KeyCheck.AGENT_PING) {
                    val models = agentModels(provider)
                    KeyVerdict.Valid(models, pickDefault(provider, models))
                } else KeyVerdict.Unknown(resp.code, errorMessage(resp.body))
                // 402/429: xizmat kalitni tanidi (autentifikatsiya o'tgan), faqat hisob/limit muammosi.
                402 -> valid(provider, KeyNote.NO_CREDIT)
                429 -> valid(provider, KeyNote.RATE_LIMITED)
                else -> KeyVerdict.Unknown(resp.code, errorMessage(resp.body))
            }
        } catch (e: IOException) {
            KeyVerdict.NoNetwork
        }
    }

    /** Perplexity sinov so'rovi: tools'siz (qidiruv to'lovi yo'q), juda qisqa javob, saqlanmaydi. */
    private fun agentPingBody(model: String): String = buildJsonObject {
        put("model", model)
        put("input", "hi")
        put("max_output_tokens", 16)
        put("store", false)
    }.toString()

    /**
     * Perplexity ro'yxati: avval presetlar (`fast`, `low`...), keyin `GET /v1/models` dagi modellar
     * (`provider/model`). Bu manzil kalitsiz ochiq; olinmasa faqat presetlar qaytadi.
     */
    private fun agentModels(provider: OnlineProvider): List<String> {
        val fetched = runCatching { parseModels(provider, get(provider, "", provider.baseUrl + "/v1/models").body) }
            .getOrDefault(emptyList())
        return (provider.staticModels + fetched).distinct()
    }

    private fun pingBody(model: String): String = buildJsonObject {
        put("model", model)
        put("max_tokens", 1)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", "hi") })
        })
    }.toString()

    /**
     * xAI `/models` 403 qaytargach: 1 tokenlik `/chat/completions` so'rovi. Chat ishlasa (2xx) kalit qabul qilinadi;
     * sinov modeli topilmasa (400/404) ham kalit qabul qilinadi (modelni foydalanuvchi qo'lda kiritadi).
     * Hali ham 401/403 bo'lsa — xizmatning izohi (kredit yo'q, kalit cheklangan...) foydalanuvchiga ko'rsatiladi.
     */
    private fun xaiChatFallback(provider: OnlineProvider, key: String, first: Resp): KeyVerdict {
        val model = provider.preferred.firstOrNull() ?: "grok-3-mini"
        val r = post(provider, key, provider.baseUrl + "/chat/completions", pingBody(model), VERIFY_READ_TIMEOUT_MS)
        return when (r.code) {
            in 200..299, 400, 404 -> KeyVerdict.Valid(emptyList(), null)
            401, 403 -> KeyVerdict.Invalid(errorMessage(r.body) ?: errorMessage(first.body))
            402 -> valid(provider, KeyNote.NO_CREDIT)
            429 -> valid(provider, KeyNote.RATE_LIMITED)
            else -> KeyVerdict.Unknown(r.code, errorMessage(r.body))
        }
    }

    /**
     * Chat oynasidagi model almashtirgich uchun: shu API kalit hozir taqdim etayotgan modellar.
     * Perplexity'da ochiq ro'yxat yo'q — qat'iy ro'yxat qaytadi (tarmoq so'rovisiz).
     * @return model identifikatorlari; tarmoq/kalit xatosida null (UI "qayta urinish" ko'rsatadi).
     */
    suspend fun listModels(provider: OnlineProvider, rawKey: String): List<String>? = withContext(Dispatchers.IO) {
        if (provider.check == KeyCheck.AGENT_PING) {
            return@withContext try { agentModels(provider) } catch (e: IOException) { provider.staticModels }
        }
        try {
            val resp = get(provider, rawKey.trim(), modelsUrl(provider))
            if (resp.code !in 200..299) return@withContext null
            parseModels(provider, resp.body).ifEmpty { fallbackModels(provider).ifEmpty { null } }
        } catch (e: IOException) {
            null
        }
    }

    /** Xizmat ro'yxati + qo'lda qo'shilganlar (qo'lda qo'shilgani tepada). Ikkalasi ham bo'sh bo'lsa — [models] (null bo'lishi mumkin). */
    fun withCustom(models: List<String>?, custom: List<String>): List<String>? {
        if (custom.isEmpty()) return models
        return (custom + models.orEmpty()).distinct()
    }

    private val MODEL_ID = Regex("^~?[A-Za-z0-9._-]+/[A-Za-z0-9._:~@+-]+$")
    private val MODEL_ID_FLAT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    /**
     * Foydalanuvchi kiritgan model nomini tozalaydi.
     * OpenRouter: `openrouter.ai/<muallif>/<model>` sahifa manzili yopishtirilgan bo'lsa ham nomning o'zini
     * ajratib oladi; format `muallif/model` bo'lmasa — null.
     * Boshqa xizmatlar (Gemini): `models/` prefiksi olib tashlanadi, nom `gemini-3.1-pro-preview` kabi bo'lishi kerak.
     */
    fun normalizeModelId(provider: OnlineProvider, raw: String): String? {
        if (provider.id != "openrouter") {
            val s = raw.trim().removePrefix("models/").trim('/')
            return s.takeIf { MODEL_ID_FLAT.matches(it) }
        }
        var s = raw.trim().substringBefore('?').substringBefore('#')
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .removePrefix("openrouter.ai/").trim('/')
        val parts = s.split('/')
        if (parts.size > 2) s = parts[0] + "/" + parts[1]
        return s.takeIf { MODEL_ID.matches(it) }
    }

    /**
     * OpenRouter: `GET /model/{muallif}/{model}` (`:free` kabi variantlar ham qabul qilinadi); topilmasa 404.
     * Zaxira: eski `/models/{muallif}/{model}/endpoints`.
     */
    suspend fun checkModel(provider: OnlineProvider, rawKey: String, model: String): ModelCheck = withContext(Dispatchers.IO) {
        try {
            val key = rawKey.trim()
            if (provider.id != "openrouter") {
                // Gemini (OpenAI-mos): `GET /models/{nom}`. Faqat 404 — "topilmadi"; boshqa javobda baribir saqlanadi.
                val r = get(provider, key, provider.baseUrl + "/models/" + model)
                return@withContext when (r.code) {
                    in 200..299 -> ModelCheck.EXISTS
                    404 -> ModelCheck.NOT_FOUND
                    else -> ModelCheck.UNKNOWN
                }
            }
            val r = get(provider, key, provider.baseUrl + "/model/" + model)
            when (r.code) {
                in 200..299 -> ModelCheck.EXISTS
                404 -> {
                    val r2 = get(provider, key, provider.baseUrl + "/models/" + model.substringBefore(':') + "/endpoints")
                    if (r2.code in 200..299) ModelCheck.EXISTS else if (r2.code == 404) ModelCheck.NOT_FOUND else ModelCheck.UNKNOWN
                }
                else -> ModelCheck.UNKNOWN
            }
        } catch (e: IOException) {
            ModelCheck.UNKNOWN
        }
    }

    /** Ro'yxat olinmaganda ko'rsatiladigan modellar: qat'iy ro'yxat, bo'lmasa ruxsat etilganlar ro'yxati. */
    fun fallbackModels(provider: OnlineProvider): List<String> =
        provider.staticModels.ifEmpty { provider.modelAllowlist }.ifEmpty { provider.extraModels }

    /** [OnlineProvider.extraModels] ro'yxat oxiriga qo'shiladi (registrga qaramay takrorlarsiz). */
    private fun List<String>.withExtras(provider: OnlineProvider): List<String> {
        if (provider.extraModels.isEmpty()) return this
        val have = map { it.lowercase() }.toSet()
        return this + provider.extraModels.filter { it.lowercase() !in have }
    }

    private fun valid(provider: OnlineProvider, note: KeyNote): KeyVerdict.Valid {
        val models = fallbackModels(provider)
        return KeyVerdict.Valid(models, pickDefault(provider, models), note)
    }

    private fun modelsUrl(provider: OnlineProvider): String =
        provider.baseUrl + "/models" + if (provider.style == ApiStyle.ANTHROPIC) "?limit=100" else ""

    private fun looksLikeBadKey(body: String): Boolean {
        val b = body.lowercase()
        return "api key not valid" in b || "api_key_invalid" in b || "invalid api key" in b || "invalid_api_key" in b
    }

    // ───────────────────────── Model ro'yxati ─────────────────────────

    private val NON_CHAT = Regex(
        "embed|whisper|tts|dall-e|moderation|rerank|guard|transcribe|speech|realtime|audio|" +
            "babbage|davinci|image|imagen|veo|sora|aqa|lyria|ocr|safeguard|orpheus|canopylabs"
    )

    // OpenAI'da /chat/completions bilan ishlamaydigan modellar (faqat Responses API / completions),
    // fine-tune modellar va chat bo'lmagan turlar. Ro'yxatda ko'rinmasin.
    private val NOT_CHAT_OPENAI = Regex(
        "instruct|codex|-pro(-|$)|deep-research|computer-use|^ft:|^ft-|^o1-preview|gpt-3\\.5-turbo-16k"
    )

    private fun parseModels(provider: OnlineProvider, body: String): List<String> {
        val raw = parseModelsRaw(provider, body).withExtras(provider)
        val allow = provider.modelAllowlist
        if (allow.isEmpty()) return raw
        // Faqat ruxsat etilgan modellar (allowlist tartibida), kalitda mavjudlari; hech biri bo'lmasa — allowlist'ning o'zi.
        val have = raw.map { it.lowercase() }.toSet()
        return allow.filter { it.lowercase() in have }.ifEmpty { allow }
    }

    private fun parseModelsRaw(provider: OnlineProvider, body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        // Odatda {"data":[...]}; ba'zi xizmatlar to'g'ridan-to'g'ri massiv qaytaradi.
        val items: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        val ids = items.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
            if (type != null && type != "chat" && type != "model") return@mapNotNull null
            (obj["id"] as? JsonPrimitive)?.contentOrNull?.removePrefix("models/")
        }.filter { it.isNotBlank() && !NON_CHAT.containsMatchIn(it.lowercase()) }
            .filter { provider.id != "openai" || !NOT_CHAT_OPENAI.containsMatchIn(it.lowercase()) }
            .distinct()

        val sorted = ids.sortedWith(
            compareByDescending<String> { provider.freeFirst && it.endsWith(":free") }.thenBy { it }
        )
        // Allowlist bo'lsa kesmaymiz: kerakli model alifbo bo'yicha 80-dan keyin tushib qolmasin.
        if (provider.modelAllowlist.isNotEmpty()) return sorted
        return sorted.take(if (provider.id == "openrouter") MAX_MODELS_LARGE_CATALOG else MAX_MODELS)
    }

    /** Standart model: avval xizmatning `preferred` bo'laklari bo'yicha (eng qisqa nom), bo'lmasa birinchisi. */
    fun pickDefault(provider: OnlineProvider, models: List<String>): String? {
        if (models.isEmpty()) return null
        val pool = if (provider.freeFirst) models.filter { it.endsWith(":free") }.ifEmpty { models } else models
        for (pref in provider.preferred) {
            pool.filter { it.lowercase().contains(pref) }.minByOrNull { it.length }?.let { return it }
        }
        return pool.first()
    }

    // ───────────────────────── Oqimli chat ─────────────────────────

    /** "To'xtatish" tugmasi: ochiq ulanishni uzadi, [streamChat] hozirgacha kelgan matnni qaytaradi. */
    fun cancel() {
        cancelled = true
        runCatching { activeConn?.disconnect() }
    }

    /**
     * @param history shu suhbatdagi oldingi xabarlar (joriy [userPrompt]siz).
     * @return to'liq javob matni; har bir bo'lak [onToken] orqali ham uzatiladi.
     */
    suspend fun streamChat(
        session: OnlineSession,
        system: String,
        history: List<StoredMessage>,
        userPrompt: String,
        onToken: (String) -> Unit,
        // Joriy xabarga biriktirilgan rasmlar (ImageStore fayl nomlari).
        images: List<String> = emptyList()
    ): String = streamChatEx(session, system, history, userPrompt, onToken, images).text

    /** [streamChat] bilan bir xil, lekin javob chiqish limitida uzilgan-uzilmaganini ham qaytaradi. */
    suspend fun streamChatEx(
        session: OnlineSession,
        system: String,
        history: List<StoredMessage>,
        userPrompt: String,
        onToken: (String) -> Unit,
        images: List<String> = emptyList()
    ): StreamResult {
        // "To'xtatish" belgisi bitta mantiqiy so'rov boshida tozalanadi (qayta urinishlar orasida emas).
        cancelled = false
        val capKey = "${session.provider.id}:${session.model}"
        val startCapValue = startCap(session.provider, session.model)
        var cap = startCapValue
        var persistCap = true
        var hist = history
        var trims = 0
        var rateRetries = 0
        while (true) {
            if (cancelled) return StreamResult("")
            try {
                val res = streamOnce(session, system, hist, userPrompt, onToken, images, cap)
                // Faqat muvaffaqiyatli so'rovdan keyin, va faqat xizmatning o'z limit xatosidan o'rganilgan bo'lsa eslab qolinadi.
                if (persistCap && cap < startCapValue) learnedCap[capKey] = cap
                return res
            } catch (e: TokenLimitRetry) {
                cap = e.next
                if (e.fromContext) persistCap = false
            } catch (e: RateLimitRetry) {
                // 429: bir necha marta kutib, qayta urinamiz (agent zanjiri limitga urilib to'xtab qolmasin).
                if (rateRetries >= RATE_LIMIT_BACKOFF_MS.size) throw OnlineException(e.message2)
                val wait = maxOf(e.waitMs, RATE_LIMIT_BACKOFF_MS[rateRetries]).coerceAtMost(60_000L)
                rateRetries++
                var waited = 0L
                while (waited < wait && !cancelled) { delay(200); waited += 200 }
            } catch (e: ContextRetry) {
                if (trims >= MAX_HISTORY_TRIMS || hist.size <= 1) {
                    throw OnlineException(
                        tr("Suhbat tarixi modelning kontekst oynasiga sig'madi. Yangi suhbat boshlang yoki konteksti kattaroq modelni tanlang.")
                    )
                }
                hist = hist.takeLast(maxOf(1, hist.size / 2))
                trims++
            }
        }
    }

    private suspend fun streamOnce(
        session: OnlineSession,
        system: String,
        history: List<StoredMessage>,
        userPrompt: String,
        onToken: (String) -> Unit,
        images: List<String>,
        cap: Int
    ): StreamResult = withContext(Dispatchers.IO) {
        val provider = session.provider
        val turns = buildTurns(history, userPrompt, images)
        // DeepSeek V4 Pro rasmni (vision) qo'llamaydi — aniq xabar beramiz (aks holda API 400 qaytaradi).
        if (provider.id == "deepseek" && session.model.contains("v4-pro") && turns.any { it.images.isNotEmpty() }) {
            throw OnlineException(tr("Bu model rasmni qo'llamaydi. Rasm uchun deepseek-flash modelini tanlang."))
        }
        val (url, body) = when (provider.style) {
            ApiStyle.OPENAI -> provider.baseUrl + "/chat/completions" to buildJsonObject {
                put("model", session.model)
                put("stream", true)
                // OpenAI'ning yangi modellari `max_tokens`ni rad etadi — ular uchun `max_completion_tokens`.
                put(if (provider.id == "openai") "max_completion_tokens" else "max_tokens", cap)
                // DeepSeek V4: thinking sukut bo'yicha yoqiq va fikr `reasoning_content` da keladi (ilova uni
                // ko'rsatmaydi) — javob oldidan uzoq jimlik bo'lmasligi uchun o'chiramiz.
                if (provider.id == "deepseek") put("thinking", buildJsonObject { put("type", "disabled") })
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", system) })
                    turns.forEach { t ->
                        add(buildJsonObject {
                            put("role", t.role)
                            if (t.images.isEmpty()) put("content", t.text)
                            else put("content", buildJsonArray {
                                // OpenAI-mos format: matn + data-URL ko'rinishidagi rasmlar.
                                if (t.text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", t.text) })
                                t.images.forEach { b64 ->
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$b64") })
                                    })
                                }
                            })
                        })
                    }
                })
            }.toString()
            ApiStyle.AGENT -> provider.baseUrl + "/v1/agent" to buildJsonObject {
                // Model (`provider/model`) yoki preset (`fast`, `low`...). Preset o'z asboblarini (web_search) olib keladi;
                // to'g'ridan-to'g'ri modelga veb-qidiruv `web_search` asbobi bilan beriladi.
                val target = agentTarget(session.model)
                if ('/' in target) put("model", target) else put("preset", target)
                put("stream", true)
                put("store", false)
                put("max_output_tokens", cap)
                put("instructions", system)
                if ('/' in target) {
                    put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
                }
                put("input", buildJsonArray {
                    turns.forEach { t ->
                        add(buildJsonObject {
                            put("type", "message")
                            put("role", t.role)
                            if (t.images.isEmpty()) put("content", t.text)
                            else put("content", buildJsonArray {
                                if (t.text.isNotBlank()) add(buildJsonObject { put("type", "input_text"); put("text", t.text) })
                                t.images.forEach { b64 ->
                                    add(buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", "data:image/jpeg;base64,$b64")
                                    })
                                }
                            })
                        })
                    }
                })
            }.toString()
            ApiStyle.ANTHROPIC -> provider.baseUrl + "/messages" to buildJsonObject {
                put("model", session.model)
                put("max_tokens", cap)
                put("stream", true)
                put("system", system)
                put("messages", buildJsonArray {
                    turns.forEach { t ->
                        add(buildJsonObject {
                            put("role", t.role)
                            if (t.images.isEmpty()) put("content", t.text)
                            else put("content", buildJsonArray {
                                // Anthropic tavsiyasi: rasmlar matndan oldin turadi.
                                t.images.forEach { b64 ->
                                    add(buildJsonObject {
                                        put("type", "image")
                                        put("source", buildJsonObject {
                                            put("type", "base64")
                                            put("media_type", "image/jpeg")
                                            put("data", b64)
                                        })
                                    })
                                }
                                if (t.text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", t.text) })
                            })
                        })
                    }
                })
            }.toString()
        }

        val conn = open(provider, session.apiKey, url, "POST", STREAM_READ_TIMEOUT_MS)
        activeConn = conn
        val sb = StringBuilder()
        var truncated = false
        var sources: List<WebSource> = emptyList()
        try {
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() }.getOrNull().orEmpty()
                // Chiqish limiti xizmat/model uchun juda katta bo'lsa — pastroq limit bilan qayta urinamiz.
                if (code == 400 || code == 413) {
                    if (looksLikeContextOverflow(errBody)) onContextOverflow(cap, errBody)
                    if (code == 400 && looksLikeTokenLimit(errBody)) nextCap(cap, errBody)?.let { throw TokenLimitRetry(it) }
                }
                // MobAgent Cloud: server o'z xato kodini qaytaradi. Faqat "busy" (bir vaqtda ko'p so'rov) qayta uriniladi;
                // tarif yo'q / limit tugagan (soatlab) holatlarida bekor kutmasdan aniq xabar ko'rsatamiz.
                if (provider.id == OnlineProviders.cloud.id && !(code == 429 && errorCode(errBody) == "busy")) {
                    throw OnlineException(cloudErrorText(code, errorCode(errBody), errorMessage(errBody)))
                }
                if (code == 429) {
                    val retryAfterMs = (conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L) * 1000L
                    throw RateLimitRetry(retryAfterMs, httpErrorText(code, errorMessage(errBody)))
                }
                var msg = httpErrorText(code, errorMessage(errBody))
                // Perplexity 401: kalit noto'g'ri/o'chirilgan YOKI hisobda kredit tugagan (hujjatga ko'ra).
                if (provider.id == "perplexity" && code == 401) msg += "\n" + tr("Perplexity 401: kalit noto'g'ri yoki o'chirilgan, yoki hisobda kredit tugagan. console.perplexity.ai/project/billing sahifasini tekshiring.")
                throw OnlineException(msg)
            }

            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue

                    obj["error"]?.let { err ->
                        if (err !is JsonNull) throw OnlineException(errorMessage(payload) ?: tr("Xizmat xato qaytardi."))
                    }
                    if (isLengthStop(provider, obj)) truncated = true
                    if (provider.style == ApiStyle.AGENT && agentType(obj) == "response.completed") {
                        sources = agentSources(obj)
                    }
                    val piece = when (provider.style) {
                        ApiStyle.OPENAI -> openAiDelta(obj)
                        ApiStyle.AGENT -> {
                            if (agentType(obj) == "response.completed") break
                            agentDelta(obj)
                        }
                        ApiStyle.ANTHROPIC -> {
                            if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "message_stop") break
                            anthropicDelta(obj)
                        }
                    }
                    if (!piece.isNullOrEmpty()) {
                        sb.append(piece)
                        onToken(piece)
                    }
                }
            }
            StreamResult(sb.toString(), truncated && !cancelled, sources)
        } catch (e: OnlineException) {
            throw e
        } catch (e: IOException) {
            when {
                cancelled -> StreamResult(sb.toString())
                sb.isNotEmpty() -> StreamResult(sb.append("\n\n").append(tr("[aloqa uzildi]")).toString())
                else -> throw OnlineException(tr("Internetga ulanib bo'lmadi. Tarmoqni tekshiring."))
            }
        } finally {
            runCatching { conn.disconnect() }
            if (activeConn === conn) activeConn = null
        }
    }

    /** Model chiqish limitiga yetib to'xtadimi: OpenAI-mos `finish_reason: "length"`, Anthropic `message_delta.stop_reason: "max_tokens"`. */
    private fun isLengthStop(provider: OnlineProvider, obj: JsonObject): Boolean = when (provider.style) {
        ApiStyle.OPENAI -> {
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull == "length"
        }
        ApiStyle.ANTHROPIC ->
            (obj["type"] as? JsonPrimitive)?.contentOrNull == "message_delta" &&
                ((obj["delta"] as? JsonObject)?.get("stop_reason") as? JsonPrimitive)?.contentOrNull == "max_tokens"
        // Agent API: `response.completed` ichida status "incomplete" (sabab: max_output_tokens yoki ko'rsatilmagan).
        ApiStyle.AGENT -> if (agentType(obj) != "response.completed") false else {
            val r = obj["response"] as? JsonObject
            val status = (r?.get("status") as? JsonPrimitive)?.contentOrNull
            val reason = ((r?.get("incomplete_details") as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
            status == "incomplete" && (reason == null || reason == "max_output_tokens")
        }
    }

    /** Eski Sonar nomlari (oldingi versiyada saqlangan) — hujjatdagi moslik jadvaliga ko'ra presetlarga o'giriladi. */
    private fun agentTarget(model: String): String = when (model) {
        "sonar", "sonar-pro" -> "fast"
        "sonar-reasoning-pro" -> "low"
        "sonar-deep-research" -> "high"
        else -> model
    }

    private fun agentType(obj: JsonObject): String? = (obj["type"] as? JsonPrimitive)?.contentOrNull

    /** Agent API oqimi: javob matni `response.output_text.delta` hodisalarining `delta` maydonida keladi. */
    private fun agentDelta(obj: JsonObject): String? =
        if (agentType(obj) == "response.output_text.delta") (obj["delta"] as? JsonPrimitive)?.contentOrNull else null

    /**
     * `response.completed` dagi manbalar: avval xabar matnidagi `url_citation` annotatsiyalari, bo'lmasa
     * `search_results` natijalari (sarlavha + manzil). Takrorlarsiz, eng ko'pi bilan [MAX_SOURCES] ta.
     */
    private fun agentSources(obj: JsonObject): List<WebSource> {
        val output = ((obj["response"] as? JsonObject)?.get("output") as? JsonArray) ?: return emptyList()
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val cited = mutableListOf<WebSource>()
        val searched = mutableListOf<WebSource>()
        for (item in output) {
            val o = item as? JsonObject ?: continue
            when (str(o, "type")) {
                "message" -> (o["content"] as? JsonArray)?.forEach { part ->
                    ((part as? JsonObject)?.get("annotations") as? JsonArray)?.forEach { a ->
                        val ann = a as? JsonObject ?: return@forEach
                        if (str(ann, "type") == "url_citation") {
                            val url = str(ann, "url") ?: return@forEach
                            cited += WebSource(str(ann, "title") ?: url, url)
                        }
                    }
                }
                "search_results" -> (o["results"] as? JsonArray)?.forEach { r ->
                    val res = r as? JsonObject ?: return@forEach
                    val url = str(res, "url") ?: return@forEach
                    searched += WebSource(str(res, "title") ?: url, url)
                }
            }
        }
        return (cited.ifEmpty { searched }).distinctBy { it.url }.take(MAX_SOURCES)
    }

    private fun openAiDelta(obj: JsonObject): String? {
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val delta = choice["delta"] as? JsonObject ?: return null
        return (delta["content"] as? JsonPrimitive)?.contentOrNull
    }

    private fun anthropicDelta(obj: JsonObject): String? {
        if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "content_block_delta") return null
        val delta = obj["delta"] as? JsonObject ?: return null
        if ((delta["type"] as? JsonPrimitive)?.contentOrNull != "text_delta") return null
        return (delta["text"] as? JsonPrimitive)?.contentOrNull
    }

    /**
     * Tarix + yangi savol -> navbatma-navbat (user/assistant) xabarlar. Anthropic va Perplexity navbatni
     * qat'iy talab qiladi: ketma-ket bir xil rol birlashtiriladi, boshidagi "assistant" tashlanadi.
     */
    private class Turn(val role: String, val text: String, val images: List<String> = emptyList())

    // Tarixdagi eski rasmlardan faqat eng oxirgi shuncha tasi qayta yuboriladi (token/trafik tejash uchun).
    private const val MAX_HISTORY_IMAGES = 4

    private fun buildTurns(history: List<StoredMessage>, userPrompt: String, newImages: List<String>): List<Turn> {
        // Tarixdagi rasmlarni (oxirgilarini) base64 qilib olamiz.
        var budget = MAX_HISTORY_IMAGES
        val histTurns = history.asReversed().map { m ->
            val imgs = if (m.fromUser && m.images.isNotEmpty() && budget > 0) {
                m.images.asReversed().mapNotNull { n -> if (budget > 0) ImageStore.base64(n)?.also { budget-- } else null }.asReversed()
            } else emptyList()
            Triple(m, imgs, 0)
        }.asReversed()
        val raw = histTurns.filter { (m, imgs, _) -> m.text.isNotBlank() || imgs.isNotEmpty() }
            .map { (m, imgs, _) ->
                when {
                    // Agent rejimi: buyruq natijasi modelga foydalanuvchi tomonidan kelgan [TERMINAL OUTPUT] sifatida beriladi.
                    m.isTerminal -> Turn("user", "[TERMINAL OUTPUT]\n" + m.text.toEnglishForModel())
                    else -> Turn(if (m.fromUser) "user" else "assistant", m.text, imgs)
                }
            } +
            Turn("user", userPrompt, newImages.mapNotNull { ImageStore.base64(it) })
        val merged = mutableListOf<Turn>()
        for (t in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.role == t.role) {
                merged[merged.lastIndex] = Turn(
                    t.role,
                    listOf(last.text, t.text).filter { it.isNotBlank() }.joinToString("\n\n"),
                    last.images + t.images
                )
            } else merged += t
        }
        while (merged.isNotEmpty() && merged.first().role != "user") merged.removeAt(0)
        return merged
    }

    // ───────────────────────── HTTP yordamchilari ─────────────────────────

    private fun open(provider: OnlineProvider, key: String, url: String, method: String, readTimeout: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = readTimeout
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "MobAgent-Android")
        // MobAgent Cloud: "kalit" — Firebase ID token (soatiga yangilanadi). Bu funksiya IO tredida chaqiriladi.
        val authKey = if (provider.id == OnlineProviders.cloud.id) {
            kotlinx.coroutines.runBlocking { com.mobagent.app.model.ApiClient.token() }.orEmpty()
        } else key
        when (provider.style) {
            // /v1/models (Perplexity) kalitsiz ochiq — bo'sh kalit bilan sarlavha yuborilmaydi.
            ApiStyle.OPENAI, ApiStyle.AGENT -> if (authKey.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $authKey")
            ApiStyle.ANTHROPIC -> {
                conn.setRequestProperty("x-api-key", authKey)
                conn.setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
            }
        }
        return conn
    }

    private fun get(provider: OnlineProvider, key: String, url: String): Resp {
        val conn = open(provider, key, url, "GET", VERIFY_READ_TIMEOUT_MS)
        return try { read(conn) } finally { conn.disconnect() }
    }

    private fun post(provider: OnlineProvider, key: String, url: String, body: String, readTimeout: Int): Resp {
        val conn = open(provider, key, url, "POST", readTimeout)
        return try {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            read(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun read(conn: HttpURLConnection): Resp {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = runCatching { stream?.bufferedReader(Charsets.UTF_8)?.readText() }.getOrNull().orEmpty()
        return Resp(code, text)
    }

    /** Xizmat javobidagi xato matnini ajratib oladi (OpenAI/Anthropic/Gemini shakllari). */
    fun errorMessage(body: String): String? {
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val obj: JsonObject = when (root) {
            is JsonObject -> root
            is JsonArray -> root.firstOrNull() as? JsonObject ?: return null
            else -> return null
        }
        fun str(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val err = obj["error"]
        return when (err) {
            is JsonObject -> str(err["message"])
            is JsonPrimitive -> str(err)
            else -> null
        } ?: str(obj["message"]) ?: str(obj["detail"])
    }

    /** Server xatosidagi `error.code` (masalan "no_plan", "limit_week", "busy"). */
    fun errorCode(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull }
    }.getOrNull()

    private fun cloudErrorText(code: Int, errCode: String?, serverMsg: String?): String = when {
        errCode == "no_plan" || code == 402 ->
            tr("Tarifingiz faol emas yoki muddati tugagan. «MobAgent agents» bo'limidan tarif tanlang.")
        errCode == "plan_too_low" ->
            tr("Bu model yuqoriroq tarifda ochiladi. Tarifni o'zgartiring yoki boshqa modelni tanlang.")
        errCode == "email_unverified" -> tr("Avval emailingizni tasdiqlang.")
        code == 401 -> tr("Sessiya tugagan. Hisobdan chiqib, qayta kiring.")
        errCode?.startsWith("limit_") == true && serverMsg != null -> serverMsg
        code == 413 -> tr("So'rov juda katta. Rasmlarni kamaytiring yoki yangi suhbat boshlang.")
        code in 500..599 -> tr("AI xizmati hozir band yoki ishlamayapti. Birozdan keyin urinib ko'ring.")
        else -> serverMsg ?: "HTTP $code"
    }

    private fun httpErrorText(code: Int, serverMsg: String?): String {
        val base = when (code) {
            401, 403 -> tr("API kalit yaroqsiz yoki bekor qilingan. «API kalitlar» bo'limida yangisini kiriting.")
            402 -> tr("Hisobda mablag' yetarli emas.")
            404 -> tr("Model topilmadi. Boshqa modelni tanlang.")
            429 -> tr("So'rovlar limiti tugadi. Birozdan keyin urinib ko'ring.")
            in 500..599 -> tr("Xizmat vaqtincha javob bermayapti (%1\$s).", code)
            else -> "HTTP $code"
        }
        return if (serverMsg != null && code != 401) "$base\n($serverMsg)" else base
    }
}
