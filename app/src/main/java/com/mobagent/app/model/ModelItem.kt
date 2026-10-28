package com.mobagent.app.model

import com.mobagent.app.i18n.tr

import com.mobagent.app.prompt.ModelFamily

/**
 * Ilgari kontekst hajmi (N_CTX) llama-android.cpp'da 4096'ga qattiq kodlangan
 * edi — uzun suhbat yoki katta hujjat bilan ishlashda bu chegaraga tez
 * yetib borilardi. Endi foydalanuvchi shu ikkitadan birini tanlashi mumkin.
 * "Kengaytirilgan" ko'proq KV-kesh xotirasi talab qiladi (RAM'da, model
 * bilan bir joyda — bu haqda pastdagi eslatmaga qarang).
 */
const val STANDARD_CTX = 4096
const val EXTENDED_CTX = 8192

/**
 * Ilgari kartada faqat "Popular" degan (mazmunsiz) belgi bor edi. Endi har bir
 * model qaysi yo'nalishga eng mos ekanligi rangli belgi (badge) sifatida
 * ko'rsatiladi — ModelStoreScreen'dagi ModelCard shu enum'ning label'ini va
 * har xil rangini o'qib chizadi.
 */
enum class ModelCategory(private val key: String) {
    CODING("Kodlash"),
    CHAT("Suhbat"),
    TRANSLATION("Tarjima"),
    REASONING("Fikrlash");

    /** Joriy tildagi nomi (har safar o'qilganda tarjima qilinadi). */
    val label: String get() = tr(key)
}

/**
 * Model "darajasi" — ro'yxatda guruhlash va foydalanuvchiga nima kutish
 * kerakligini aytish uchun. Parametr soniga va agent-qobiliyatiga qarab
 * ModelItem.tier avtomatik hisoblanadi (qo'lda belgilash shart emas).
 *
 *  FAST  — Tezkor: faqat Chat, bitta aniq yo'nalishga (ModelRole) sozlangan.
 *  AGENT — 1.5B-2B: ixcham agent rejimi (fayl asboblari + grammatika).
 *  POWER — 3B va undan yuqori: to'liq agent, lekin ko'p RAM talab qiladi.
 */
enum class ModelTier(private val key: String) {
    FAST("Tezkor"),
    AGENT("Agent"),
    POWER("Kuchli");

    val label: String get() = tr(key)
}

/**
 * Admin paneldan belgilanadigan model belgisi (server katalogida "badge").
 * Noma'lum qiymat kelsa belgi ko'rsatilmaydi.
 */
enum class ModelBadge(private val key: String) {
    RECOMMENDED("Tavsiya etilgan"),
    NEW("Yangi");

    val label: String get() = tr(key)
}

/**
 * Tezkor (FAST) modellarning aniq yo'nalishi. Kichik model hamma narsani
 * yaxshi qila olmaydi — shuning uchun har biriga bitta vazifa va unga mos
 * qisqa inglizcha system prompt beriladi (prompt/ModelPromptStrategy.kt).
 */
enum class ModelRole(private val key: String) {
    QUICK_ANSWERS("Qisqa javoblar"),
    CODE_HELPER("Kod yordamchisi"),
    TEXT_WRITER("Matn yozish"),
    MATH_LOGIC("Mantiq va hisob");

    val label: String get() = tr(key)
}

data class ModelItem(
    val id: String,
    val name: String,
    val downloadUrl: String,
    val direction: String,
    val category: ModelCategory,
    val baseSizeMb: Long,
    val baseRamMb: Long,
    val minPhoneClass: String,
    val family: ModelFamily = ModelFamily.GENERIC,
    // Parametr soni (milliard hisobida) — buyruq/agent qobiliyatini
    // yoqish-o'chirishni shu bilan belgilaymiz (pastga qarang).
    val paramsBillion: Double,
    // Modelning o'zi ko'tara oladigan eng katta kontekst (token)
    val maxContext: Int = MAX_CONTEXT,
    // Faqat Tezkor (FAST) modellar uchun: qaysi aniq vazifaga sozlangan
    val role: ModelRole? = null,
    // Server katalogidan: yuklangan fayl shu SHA-256 bilan tekshiriladi (null = tekshirilmaydi)
    val sha256: String? = null,
    val sizeBytes: Long = 0L,
    // Yulduzchalar soni (faqat server katalogida bo'ladi)
    val stars: Int = 0,
    // Server katalogidan: Chat rejimidagi modelga vazifa beruvchi system prompt (null = ilovadagi standart)
    val systemPrompt: String? = null,
    // Server katalogidan: Agent rejimidagi modelga qo'shimcha ko'rsatma (standart agent promptining OXIRIGA qo'shiladi; null = qo'shilmaydi)
    val agentPrompt: String? = null,
    // Server katalogidan: «Tavsiya etilgan» / «Yangi» belgisi (null = belgisiz)
    val badge: ModelBadge? = null
) {
    val tier: ModelTier
        get() = when {
            !isAgentCapable -> ModelTier.FAST
            isCompactAgent -> ModelTier.AGENT
            else -> ModelTier.POWER
        }

    val totalDownloadSizeMb: Long
        get() = baseSizeMb

    // Kichik modellar (1B-2B) buyruq/tool-call formatini ishonchli
    // bajara olmaydi — noto'g'ri JSON, adashgan buyruqlar yoki xato
    // xulosalarga olib keladi. Shuning uchun ular FAQAT suhbat rejimida
    // ishlaydi (tizim ko'rsatmasida JSON/buyruq haqida hech narsa
    // bo'lmaydi, va AgentOrchestrator baribir ehtimoliy JSON'ni hech
    // qachon tool-call sifatida ishga tushirmaydi — ModelPromptStrategy.kt
    // va AgentOrchestrator.kt'ga qarang). 3B va undan yuqori modellar —
    // "Agent" rejimida, buyruq bajarish (Termux-uslubidagi shell) bilan.
    //
    // YANGILANDI: 1.5B-2B modellar ham Agent rejimiga o'tdi, lekin IXCHAM
    // rejimda (isCompactAgent): qisqa prompt + faqat fayl asboblari (bash
    // yo'q) + grammatika (ToolGrammar.COMPACT). 1B va DeepSeek-R1 (uzun
    // <think> yozadi, grammatika ishlamaydi) hali faqat Chat.
    val isAgentCapable: Boolean
        get() = paramsBillion >= 3.0 ||
            (paramsBillion >= 1.5 && family != ModelFamily.DEEPSEEK_R1)

    val isCompactAgent: Boolean
        get() = isAgentCapable && paramsBillion < 3.0

    val modeLabel: String
        get() = if (isAgentCapable) "Agent" else "Chat"

    val contextSize: Int
        get() = minOf(ContextPreference.value, maxContext)

    // Taxminiy: KV-kesh xotirasi kontekst hajmiga deyarli chiziqli proporsional
    // o'sadi. Aniq koeffitsient modelning qatlam/head soniga bog'liq, shuning
    // uchun bu yerda ehtiyotkorlik uchun "standart" RAM ehtiyojining ~40%'ini
    // qo'shimcha sifatida hisoblaymiz (2x kontekst uchun taxminiy o'sish).
    val estimatedRamRequiredMb: Long
        get() {
            val factor = (contextSize / STANDARD_CTX.toDouble() - 1.0).coerceAtLeast(0.0)
            return baseRamMb + (baseRamMb * 0.4 * factor).toLong()
        }
}

/**
 * SKILL hujjatidagi 1-bo'lim: model katalogi va resurs matritsasi.
 * Yangi model qo'shish uchun shunchaki quyidagi ro'yxatga yana bitta
 * ModelItem(...) qo'shing — boshqa hech narsani o'zgartirish shart emas,
 * ModelStoreScreen ro'yxatni avtomatik chizadi.
 */
object ModelCatalog {
    /** Serverdan olingan (yoki keshdagi) ro'yxat. null bo'lsa o'rnatilgan ro'yxat ishlatiladi. */
    @Volatile
    var remote: List<ModelItem>? = null

    /** Foydalanuvchi qo'lda (Hugging Face yoki fayldan) qo'shgan modellar. */
    @Volatile
    var custom: List<ModelItem> = emptyList()

    val models: List<ModelItem> get() = (remote ?: builtIn) + custom

    /** Server ishlamasa yoki hali olinmagan bo'lsa ishlatiladigan o'rnatilgan ro'yxat. */
    val builtIn = listOf(
        // ============================================================
        // 1) TEZKOR (<=1B va R1) — faqat Chat, har biri BITTA aniq yo'nalishga
        //    (role) sozlangan. Kichik model agent bo'la olmaydi: u bitta vazifani
        //    aniq prompt bilan yaxshi bajaradi. RAM kam kerak — hamma telefonda ishlaydi.
        // ============================================================
        ModelItem(
            id = "qwen2.5-0.5b",
            name = "Qwen-2.5-0.5B",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            direction = "Eng yengil model — oddiy savollarga qisqa javoblar",
            category = ModelCategory.CHAT,
            baseSizeMb = 398, baseRamMb = 800,
            minPhoneClass = "3 GB+ RAM (eng arzon telefonlar ham)",
            family = ModelFamily.QWEN,
            paramsBillion = 0.5,
            role = ModelRole.QUICK_ANSWERS
        ),
        ModelItem(
            id = "qwen2.5-coder-0.5b",
            name = "Qwen-2.5-Coder-0.5B",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            direction = "Kichik kod yordamchisi — qisqa kod va xatoni tushuntirish",
            category = ModelCategory.CODING,
            baseSizeMb = 398, baseRamMb = 800,
            minPhoneClass = "3 GB+ RAM (eng arzon telefonlar ham)",
            family = ModelFamily.QWEN,
            paramsBillion = 0.5,
            role = ModelRole.CODE_HELPER
        ),
        ModelItem(
            id = "llama-3.2-1b",
            name = "Llama-3.2-1B",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            direction = "Tez va yengil — xat, post va qisqa matn yozish, qisqartirish",
            category = ModelCategory.CHAT,
            baseSizeMb = 808, baseRamMb = 1400,
            minPhoneClass = "4 GB+ RAM (deyarli har qanday zamonaviy telefon)",
            family = ModelFamily.LLAMA3,
            paramsBillion = 1.0,
            role = ModelRole.TEXT_WRITER
        ),
        ModelItem(
            id = "deepseek-r1-distill-1.5b",
            name = "DeepSeek-R1-Distill-1.5B",
            downloadUrl = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            direction = "Qadam-baqadam mantiqiy fikrlash (Reasoning)",
            category = ModelCategory.REASONING,
            baseSizeMb = 1200, baseRamMb = 2000,
            minPhoneClass = "4 GB+ RAM",
            family = ModelFamily.DEEPSEEK_R1,
            paramsBillion = 1.5,
            role = ModelRole.MATH_LOGIC
        ),
        // ============================================================
        // 2) AGENT (1.5B-2B) — ixcham agent rejimi: qisqa prompt, faqat fayl
        //    asboblari va grammatika. Qurilma uchun eng muvozanatli tanlov.
        // ============================================================
        ModelItem(
            id = "qwen2.5-coder-1.5b",
            name = "Qwen-2.5-Coder-1.5B",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            direction = "Kod yozish, Bash buyruqlar, avtomatizatsiya",
            category = ModelCategory.CODING,
            baseSizeMb = 1100, baseRamMb = 1800,
            minPhoneClass = "4 GB+ RAM",
            family = ModelFamily.QWEN,
            paramsBillion = 1.5
        ),
        ModelItem(
            id = "gemma-2-2b",
            name = "Gemma-2-2B",
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            direction = "Tarjima, qisqartirish, umumiy bilim",
            category = ModelCategory.TRANSLATION,
            baseSizeMb = 1600, baseRamMb = 2400,
            minPhoneClass = "4 GB+ RAM",
            family = ModelFamily.GEMMA,
            paramsBillion = 2.0,
            maxContext = 8192
        ),
        // ============================================================
        // 3) KUCHLI (3B+) — to'liq agent, lekin 6-8 GB+ RAM kerak. Kuchsiz
        //    telefonlarda qotib qolishi mumkin, shuning uchun ro'yxat oxirida.
        // ============================================================
        ModelItem(
            id = "qwen2.5-coder-3b",
            name = "Qwen-2.5-Coder-3B",
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
            direction = "Kod yozish, Bash buyruqlar — 1.5B'ga qaraganda ancha ishonchli",
            category = ModelCategory.CODING,
            baseSizeMb = 1930, baseRamMb = 2800,
            minPhoneClass = "6 GB+ RAM (Snapdragon 695 / 7-seriya)",
            family = ModelFamily.QWEN,
            paramsBillion = 3.0
        ),
        ModelItem(
            id = "stable-code-instruct-3b",
            name = "Stable-Code-Instruct-3B",
            downloadUrl = "https://huggingface.co/QuantFactory/stable-code-instruct-3b-GGUF/resolve/main/stable-code-instruct-3b.Q4_K_M.gguf",
            direction = "Stability AI'ning kod modeli — SQL va ko'p tilli kod, ChatML",
            category = ModelCategory.CODING,
            // MUHIM TUZATISH: bu model GQA'siz (to'liq multi-head attention:
            // 32 qatlam x 2560 kenglik), shuning uchun KV-kesh xotirasi Qwen2.5-3B
            // kabi GQA modellardan ~9 baravar KATTA: 1 token ~320 KB, ya'ni
            // 4096 kontekst ~1.3 GB (8192 da ~2.6 GB, 16384 da ~5 GB). Ilgari
            // baseRamMb=2600 va kontekst cheklovi yo'q edi — 6 GB telefonlarda
            // xotira yetmay, model "xotiraga yuklanmoqda..." holatida qotib qolardi.
            baseSizeMb = 1710, baseRamMb = 3400,
            minPhoneClass = "8 GB+ RAM (yuqori o'rta sinf telefon)",
            maxContext = 4096,
            // Rasmiy hujjatida ChatML (<|im_start|>/<|im_end|>) shabloni
            // tasdiqlangan — ChatMlPromptStrategy bilan to'g'ridan-to'g'ri mos.
            family = ModelFamily.QWEN,
            paramsBillion = 3.0
        ),
        ModelItem(
            id = "phi-3.5-mini",
            name = "Phi-3.5-Mini (3.8B)",
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            direction = "Microsoft'ning kod va mantiqiy fikrlashda kuchli kichik modeli",
            // Ilgari REASONING edi — lekin bu model kod yozishda ham juda
            // kuchli (Microsoft'ning o'z HumanEval natijalari buni
            // tasdiqlaydi), shuning uchun Agent/kod bo'limiga o'tkazildi.
            category = ModelCategory.CODING,
            baseSizeMb = 2390, baseRamMb = 3400,
            minPhoneClass = "8 GB+ RAM (yuqori o'rta sinf telefon)",
            family = ModelFamily.PHI3,
            paramsBillion = 3.8
        ),
        ModelItem(
            id = "jan-code-4b",
            name = "Jan-Code-4B",
            downloadUrl = "https://huggingface.co/janhq/Jan-code-4b-gguf/resolve/main/Jan-code-4b-Q4_K_M.gguf",
            direction = "Qwen3-4B negizida, MAXSUS kod uchun o'qitilgan — Jan.ai'ning agent/sub-agent modeli",
            category = ModelCategory.CODING,
            baseSizeMb = 2720, baseRamMb = 3900,
            minPhoneClass = "8 GB+ RAM (yuqori o'rta sinf telefon)",
            // Qwen3-4B-Instruct'dan fine-tune qilingan — demak ChatML.
            family = ModelFamily.QWEN,
            paramsBillion = 4.0
        ),
        ModelItem(
            id = "qwen3-4b",
            name = "Qwen3-4B",
            downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/main/Qwen_Qwen3-4B-Q4_K_M.gguf",
            direction = "Qwen'ning yangi avlodi — mantiqiy fikrlash (\"thinking\" rejimi) va buyruqlarga aniq amal qilish",
            category = ModelCategory.REASONING,
            baseSizeMb = 2500, baseRamMb = 3600,
            minPhoneClass = "8 GB+ RAM (yuqori o'rta sinf telefon)",
            // ChatML — rasmiy hujjatida tasdiqlangan. QWEN oilasi endi
            // <think> JSON'dan oldin chiqmasligi uchun maxsus ko'rsatma
            // bilan (THINK_BEFORE_JSON_GUARD) ishlaydi — ModelPromptStrategy.kt.
            family = ModelFamily.QWEN,
            paramsBillion = 4.0
        ),
        ModelItem(
            id = "gemma-3-4b-it",
            name = "Gemma-3-4B",
            downloadUrl = "https://huggingface.co/l3utterfly/gemma-3-4b-it-gguf/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
            direction = "Google'ning yangilangan modeli — ko'p tilli, uzun kontekst (128K)",
            category = ModelCategory.TRANSLATION,
            baseSizeMb = 2375, baseRamMb = 3600,
            minPhoneClass = "8 GB+ RAM (yuqori o'rta sinf telefon)",
            family = ModelFamily.GEMMA,
            paramsBillion = 4.0
        ),
        ModelItem(
            id = "qwen2.5-7b",
            name = "Qwen-2.5-7B (Kuchli)",
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            direction = "Eng kuchli umumiy model — murakkab suhbat va vazifalar uchun",
            category = ModelCategory.CHAT,
            baseSizeMb = 4680, baseRamMb = 6800,
            minPhoneClass = "14 GB+ RAM (yuqori sinf telefon, Snapdragon 8-seriya)",
            family = ModelFamily.QWEN,
            paramsBillion = 7.0
        ),
        ModelItem(
            id = "mistral-7b-v0.3",
            name = "Mistral-7B-Instruct-v0.3",
            downloadUrl = "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            direction = "Ko'p tilli, kuchli umumiy model — yuqori sinf telefonlar uchun",
            category = ModelCategory.CHAT,
            baseSizeMb = 4370, baseRamMb = 6400,
            minPhoneClass = "14 GB+ RAM (yuqori sinf telefon)",
            family = ModelFamily.MISTRAL,
            paramsBillion = 7.0
        )
    )

    // Admin modelni katalogdan o'chirgan bo'lsa ham, telefonda yuklangan model ishlashda davom etishi uchun
    // o'rnatilgan ro'yxatga ham qaraymiz.
    fun byId(id: String?): ModelItem? =
        models.firstOrNull { it.id == id } ?: builtIn.firstOrNull { it.id == id } ?: custom.firstOrNull { it.id == id }
}
