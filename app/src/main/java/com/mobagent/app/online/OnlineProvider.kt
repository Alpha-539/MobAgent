package com.mobagent.app.online

/** Xizmat qaysi protokolda gaplashadi. */
enum class ApiStyle {
    /** OpenAI-mos: `POST {base}/chat/completions`, `Authorization: Bearer <kalit>`. */
    OPENAI,

    /** Anthropic Messages API: `POST {base}/messages`, `x-api-key` + `anthropic-version`. */
    ANTHROPIC,

    /**
     * Perplexity Agent API: `POST {base}/v1/agent`, `Authorization: Bearer <kalit>`. Veb-qidiruvli (grounded) javob;
     * `model` yoki `preset` ("fast"/"low"/"medium"/"high"/"xhigh") tanlanadi, oqim — `response.output_text.delta` hodisalari.
     */
    AGENT
}

/** Kalit to'g'riligi qaysi so'rov bilan tekshiriladi (har xizmatning o'z hujjatiga ko'ra). */
enum class KeyCheck {
    /** `GET {base}/models` — kalit noto'g'ri bo'lsa 401 qaytaradi. */
    MODELS,

    /**
     * `GET {base}/key` (OpenRouter). Diqqat: OpenRouter'ning `/models` manzili kalitsiz ham
     * (soxta kalit bilan ham) 200 qaytaradi, shuning uchun u kalitni tekshirmaydi.
     */
    OPENROUTER_KEY,

    /**
     * Perplexity: `GET /v1/models` kalitsiz ham ochiq, shuning uchun kalit `POST /v1/agent` ga yuborilgan
     * tools'siz, ~16 tokenlik so'rov bilan tekshiriladi (autentifikatsiya model tekshiruvidan oldin o'tadi).
     */
    AGENT_PING
}

/**
 * Onlayn rejimda foydalanuvchi o'z API kaliti bilan ulana oladigan xizmat.
 */
data class OnlineProvider(
    val id: String,
    val name: String,
    /** Kalit kiritish maydonidagi namuna (kalit prefiksi). */
    val keyHint: String,
    val baseUrl: String,
    /** Kalit olinadigan sahifa (foydalanuvchiga ko'rsatish uchun). */
    val keyPage: String,
    val style: ApiStyle = ApiStyle.OPENAI,
    val check: KeyCheck = KeyCheck.MODELS,
    /** Kalit boshlanishi kerak bo'lgan prefiks (faqat xato xabarini aniqroq qilish uchun; null — noma'lum). */
    val keyPrefix: String? = null,
    /** Ochiq model ro'yxati bo'lmagan xizmatlar uchun qat'iy ro'yxat. */
    val staticModels: List<String> = emptyList(),
    /** Ro'yxatdan standart model tanlashda birinchi bo'lib qidiriladigan bo'laklar (kichik harfda). */
    val preferred: List<String> = emptyList(),
    /** Ro'yxatda bepul modellar (`:free`) birinchi chiqsinmi (OpenRouter). */
    val freeFirst: Boolean = false,
    /** Foydalanuvchi ro'yxatda yo'q model nomini qo'lda qo'sha oladi (OpenRouter, Gemini). */
    val allowCustomModels: Boolean = false,
    /**
     * Bo'sh bo'lmasa, xizmatning `/models` javobidan faqat shu modellar ko'rsatiladi (shu tartibda).
     * Hech biri javobda topilmasa — shu ro'yxatning o'zi ko'rsatiladi. Qolgan modellar qo'lda qo'shiladi.
     */
    val modelAllowlist: List<String> = emptyList(),
    /**
     * Har doim ro'yxatga qo'shiladigan modellar (xizmatning `/models` javobida bo'lmasa ham; bor bo'lsa takrorlanmaydi).
     * Kalitda ruxsat bo'lmasa, model ishlatilganda xizmatning o'z xatosi chiqadi.
     */
    val extraModels: List<String> = emptyList(),
    /** Qo'lda model qo'shish maydonidagi namuna. */
    val customModelExample: String = "openai/gpt-4o",
    /** [KeyCheck.AGENT_PING] uchun sinov modeli (`provider/model`). */
    val pingModel: String? = null
)

object OnlineProviders {
    val all: List<OnlineProvider> = listOf(
        OnlineProvider(
            "openai", "OpenAI", "sk-...", "https://api.openai.com/v1", "platform.openai.com/api-keys",
            keyPrefix = "sk-", preferred = listOf("gpt-5-mini", "gpt-4.1-mini", "gpt-4o-mini", "gpt-5")
        ),
        OnlineProvider(
            "deepseek", "DeepSeek", "sk-...", "https://api.deepseek.com", "platform.deepseek.com/api_keys",
            keyPrefix = "sk-", preferred = listOf("deepseek-flash", "deepseek-v4-pro")
        ),
        OnlineProvider(
            "openrouter", "OpenRouter", "sk-or-...", "https://openrouter.ai/api/v1", "openrouter.ai/keys",
            check = KeyCheck.OPENROUTER_KEY, keyPrefix = "sk-or-", freeFirst = true, allowCustomModels = true,
            preferred = listOf("gpt-oss", "llama", "gemini", "qwen")
        ),
        OnlineProvider(
            "groq", "Groq", "gsk_...", "https://api.groq.com/openai/v1", "console.groq.com/keys",
            keyPrefix = "gsk_", preferred = listOf("llama-3.3-70b", "llama-3.1-8b", "gpt-oss")
        ),
        OnlineProvider(
            "anthropic", "Anthropic (Claude)", "sk-ant-...", "https://api.anthropic.com/v1",
            "console.anthropic.com/settings/keys",
            style = ApiStyle.ANTHROPIC, keyPrefix = "sk-ant-", preferred = listOf("haiku", "sonnet")
        ),
        OnlineProvider(
            "gemini", "Google Gemini", "AIza...", "https://generativelanguage.googleapis.com/v1beta/openai",
            "aistudio.google.com/apikey",
            keyPrefix = "AIza", preferred = listOf("gemini-2.5-flash", "gemini-flash", "gemini"),
            allowCustomModels = true, customModelExample = "gemini-3.1-pro-preview",
            modelAllowlist = listOf(
                "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3-flash-preview", "gemini-3-flash-lite",
                "gemini-3.1-flash-lite-preview", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.6-flash",
                "gemini-3.7-flash", "gemini-3.8-flash", "gemini-flash-latest", "gemini-flash-lite-latest",
                "gemini-robotics-er-2-preview"
            )
        ),
        OnlineProvider(
            "xai", "xAI (Grok)", "xai-...", "https://api.x.ai/v1", "console.x.ai",
            keyPrefix = "xai-", preferred = listOf("grok-4-fast", "grok-3-mini", "grok")
        ),
        OnlineProvider(
            "mistral", "Mistral AI", "API kalit", "https://api.mistral.ai/v1", "console.mistral.ai/api-keys",
            preferred = listOf("mistral-small", "mistral-medium", "mistral-large")
        ),
        OnlineProvider(
            "perplexity", "Perplexity", "pplx-...", "https://api.perplexity.ai", "console.perplexity.ai",
            style = ApiStyle.AGENT, check = KeyCheck.AGENT_PING, keyPrefix = "pplx-",
            // Presetlar (`provider/` belgisiz nom) ro'yxat boshida; undan keyin /v1/models'dagi modellar.
            staticModels = listOf("fast", "low", "medium", "high", "xhigh"),
            preferred = listOf("fast"), pingModel = "openai/gpt-6-luna"
        ),
        OnlineProvider(
            "cerebras", "Cerebras", "csk-...", "https://api.cerebras.ai/v1", "cloud.cerebras.ai",
            keyPrefix = "csk-", preferred = listOf("llama-3.3-70b", "llama3.1-8b", "gpt-oss", "qwen"),
            allowCustomModels = true, customModelExample = "qwen-3.8-27b",
            extraModels = listOf("qwen-3.8-27b", "gemma-4-31b")
        ),
        OnlineProvider(
            "meta", "Meta (Llama API)", "API kalit", "https://api.llama.com/compat/v1",
            "llama.developer.meta.com",
            preferred = listOf("llama-4-maverick", "llama-4-scout", "llama-3.3-70b"),
            // /models javobida bo'lmasa ham tanlash mumkin bo'lsin (Meta OpenAI-mos endpointi nomlari).
            extraModels = listOf(
                "Llama-4-Maverick-17B-128E-Instruct-FP8", "Llama-4-Scout-17B-16E-Instruct-FP8",
                "Llama-3.3-70B-Instruct", "Llama-3.3-8B-Instruct"
            ),
            allowCustomModels = true, customModelExample = "Llama-4-Maverick-17B-128E-Instruct-FP8"
        )
    )

    /**
     * MobAgent Cloud (pullik tarif): `all` ro'yxatiga KIRMAYDI (foydalanuvchi kalit kiritmaydi, kalitlar ekranida ko'rinmaydi).
     * "Kalit" o'rniga Firebase ID token yuboriladi — OnlineApi.open() uni har so'rovda yangilaydi.
     */
    val cloud = OnlineProvider(
        "mobagent", "MobAgent Cloud", "", com.mobagent.app.model.ApiConfig.BASE_URL + "/v1", "",
        preferred = listOf("claude-haiku-4-5", "gemini-2.5-flash", "gpt-5-mini")
    )

    fun byId(id: String): OnlineProvider? = all.firstOrNull { it.id == id } ?: cloud.takeIf { it.id == id }
}

/**
 * Onlayn suhbat sozlamasi. Suhbat tarixida (modelId maydonida) `online:<xizmat>:<model>` ko'rinishida
 * saqlanadi — shu bilan eski onlayn suhbat qayta ochilganda ham qaysi xizmat/model ekani ma'lum bo'ladi.
 */
data class OnlineChatConfig(val providerId: String, val model: String) {
    val provider: OnlineProvider? get() = OnlineProviders.byId(providerId)
    fun encode(): String = "$PREFIX$providerId:$model"

    companion object {
        const val PREFIX = "online:"

        /** `online:groq:llama-3.3-70b-versatile` -> config; boshqa (oflayn model id yoki null) -> null. */
        fun parse(modelId: String?): OnlineChatConfig? {
            if (modelId == null || !modelId.startsWith(PREFIX)) return null
            val rest = modelId.removePrefix(PREFIX)
            val sep = rest.indexOf(':')
            if (sep <= 0 || sep == rest.length - 1) return null
            val cfg = OnlineChatConfig(rest.substring(0, sep), rest.substring(sep + 1))
            return if (cfg.provider != null) cfg else null
        }
    }
}
