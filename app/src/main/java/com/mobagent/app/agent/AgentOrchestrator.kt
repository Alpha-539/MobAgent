package com.mobagent.app.agent

import com.mobagent.app.engine.LlamaEngine
import com.mobagent.app.online.OnlineApi
import com.mobagent.app.online.OnlineSession
import com.mobagent.app.online.StreamResult
import com.mobagent.app.online.WebSource
import com.mobagent.app.i18n.tr
import com.mobagent.app.history.StoredMessage
import com.mobagent.app.prompt.toEnglishForModel
import com.mobagent.app.prompt.GenericPromptStrategy
import com.mobagent.app.prompt.ModelPromptStrategy
import com.mobagent.app.prompt.trimHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

@Serializable
data class ToolCall(
    val action: String,
    val cmd: String? = null,
    val path: String? = null,
    // write_file: to'liq matn. edit_file: `find` (faylda BIR marta uchraydigan aniq matn) -> `replace`.
    val content: String? = null,
    val find: String? = null,
    val replace: String? = null,
    // rename: yangi yo'l (eski yo'l `path`da).
    val to: String? = null,
    // web_search: qidiruv so'rovi. web_fetch: sahifa manzili (curl'siz, ilovaning o'zi yuklaydi).
    val query: String? = null,
    val url: String? = null
) {
    /** Tasdiqlash kartasida ko'rsatiladigan qisqa tavsif. */
    fun describe(): String = when (action) {
        "write_file" -> "write_file $path  (${content.orEmpty().lines().size} qator)"
        "edit_file" -> "edit_file $path"
        "list_dir" -> "list_dir $path"
        "read_file" -> "read_file $path"
        "delete" -> "delete $path"
        "rename" -> "rename $path -> $to"
        "web_search" -> "web_search ${query.orEmpty()}"
        "web_fetch" -> "web_fetch ${url.orEmpty()}"
        else -> cmd.orEmpty()
    }
}

sealed class AgentStep {
    data class AssistantText(val text: String) : AgentStep()
    // preamble: model JSON'dan OLDIN yozgan izoh ("Fayl yarataman:" kabi). Ilgari
    // u tashlab yuborilardi; endi buyruq kartasi bilan BITTA javobda ko'rsatiladi.
    data class ToolRequest(val call: ToolCall, val rawJson: String, val preamble: String = "") : AgentStep()
}

// Kontekst oynasi endi sozlanadigan (ModelStoreScreen'dagi "Standart /
// Kengaytirilgan" tanlovi, LlamaEngine.loadModel'ga nCtx sifatida boradi).
// Xavfsizlik uchun tarixni belgilar soni bo'yicha kesib olamiz — bu aniq
// token hisobi emas, lekin taxminan 1 token ~ 3-4 belgi bo'lgani uchun
// n_ctx'dan ancha pastda qoladi. Tizim ko'rsatmasi bu limitga kirmaydi —
// u ModelPromptStrategy orqali har doim to'liq saqlanadi.
private const val HISTORY_CHARS_PER_CTX_TOKEN = 6000.0 / 4096.0

/**
 * Model fikridan tool-call'ni ajratib, kerak bo'lsa CommandExecutor'ga yo'naltiradi.
 * Xavfli buyruqlar UI qatlamidagi tasdiqlash dialogidan (Permission & Execution Guard)
 * o'tgandan keyingina bajariladi — bu yerda faqat parsing va routing logikasi bor.
 *
 * @param promptStrategy joriy modelning oilasiga mos chat-shablon va tizim
 * ko'rsatmasini beradi (Qwen/Llama-3/Gemma/DeepSeek-R1 — ModelPromptStrategy.kt).
 * Barcha oilalar bir xil JSON ({"action":"execute_bash","cmd":"..."}) chiqaradi —
 * farq faqat shu JSON qanday chat-teglar bilan o'ralishida.
 * @param nCtx modelning kontekst oynasi (token) — LlamaEngine'dagi bilan bir xil
 * bo'lishi kerak, aks holda tarix budjeti noto'g'ri hisoblanadi.
 */
class AgentOrchestrator(
    private val engine: LlamaEngine,
    private val executor: CommandExecutor,
    private val promptStrategy: ModelPromptStrategy = GenericPromptStrategy(),
    private val nCtx: Int = com.mobagent.app.model.STANDARD_CTX,
    // false bo'lsa (1B-2B modellar), parseStep ehtimoliy JSON'ni HECH QACHON
    // tool-call sifatida ishga tushirmaydi — bu promptStrategy'dagi
    // CHAT_ONLY_SYSTEM'dan MUSTAQIL, qo'shimcha xavfsizlik qatlami: hatto
    // model (o'z bilimidan kelib chiqib) tasodifan JSON'ga o'xshash narsa
    // yozsa ham, buyruq bajarilmaydi, oddiy matn sifatida ko'rsatiladi.
    private val agentCapable: Boolean = true,
    // GBNF grammatika (ToolGrammar.GRAMMAR). null bo'lsa generatsiya erkin
    // (masalan DeepSeek-R1: u JSON'dan oldin uzun <think> yozadi, grammatika
    // buni keraksiz cheklaydi). Chat rejimida (agentCapable=false) har doim ishlatilmaydi.
    private val toolGrammar: String? = null,
    // null bo'lmasa — javob lokal model o'rniga onlayn xizmatdan (foydalanuvchining o'z API kaliti bilan)
    // oqimli olinadi. agentCapable=true bo'lsa onlayn model ham asboblardan (tool-call) foydalanadi.
    private val online: OnlineSession? = null,
    // Onlayn agent prompti uchun: buyruqlar qaysi muhitda (Linux/eski shell) bajariladi.
    private val linuxMode: com.mobagent.app.prompt.LinuxPromptMode = com.mobagent.app.prompt.LinuxPromptMode.NONE
) {
    private val activeGrammar: String? = if (agentCapable) toolGrammar else null
    private val json = Json { ignoreUnknownKeys = true }
    private val maxHistoryChars = (nCtx * HISTORY_CHARS_PER_CTX_TOKEN).toInt()

    /**
     * @param history shu suhbatdagi oldingi xabarlar (joriy userPrompt'siz).
     * Har chaqiruvda llama-android.cpp KV-keshni tozalagani uchun, oldingi
     * bosqichlarni "eslash" uchun ularni promptga matn sifatida qayta qo'shamiz.
     */
    suspend fun think(
        userPrompt: String,
        history: List<StoredMessage> = emptyList(),
        onToken: (String) -> Unit = {},
        images: List<String> = emptyList()
    ): AgentStep {
        online?.let { return thinkOnline(it, userPrompt, history, onToken, images) }
        val fullPrompt = promptStrategy.buildPrompt(trimHistory(history, maxHistoryChars), userPrompt)
        val sb = StringBuilder()
        // Ilgari .fold(...) BARCHA token'larni yig'ib, generatsiya TO'LIQ
        // tugagandan keyingina UI'ga bitta butun matn sifatida qaytarardi.
        // Endi har bir token kelgan zahoti onToken orqali uzatiladi.
        engine.generateStream(fullPrompt, activeGrammar).collect { tok ->
            sb.append(tok)
            onToken(tok)
        }
        return parseStep(sb.toString())
    }

    private suspend fun thinkOnline(
        session: OnlineSession,
        userPrompt: String,
        history: List<StoredMessage>,
        onToken: (String) -> Unit,
        images: List<String>
    ): AgentStep {
        val trimmed = trimHistory(history, ONLINE_HISTORY_CHARS)
        val first = OnlineApi.streamChatEx(session, onlineSystem(), trimmed, userPrompt, onToken, images)
        // Qayta urinish jim (onToken yo'q): birinchi urinishning matni allaqachon ekranda, takrorlanmasin.
        return resolveOnline(first) { notice ->
            OnlineApi.streamChatEx(session, onlineSystem(), trimmed, userPrompt + notice, {}, images)
        }
    }

    /**
     * Onlayn javob chiqish limitida uzilgan bo'lsa: tool-call JSON'i yarim yo'lda qolgan bo'lsa, modelga
     * [MAX_TRUNCATION_RETRIES] marta avtomatik qayta so'raladi (avval qisqa skelet, keyin edit_file bo'laklari).
     * Baribir uzilsa (yoki oddiy matn kesilsa) — ekranda ogohlantirish chiqadi.
     */
    private suspend fun resolveOnline(first: StreamResult, retry: suspend (String) -> StreamResult): AgentStep {
        var res = first
        var step = parseStep(res.text)
        var attempts = 0
        while (step is AgentStep.AssistantText && attempts < MAX_TRUNCATION_RETRIES &&
            ((res.truncated && isCutToolCall(res.text)) || (!res.truncated && isMalformedToolCall(res.text)))
        ) {
            attempts++
            res = retry(if (res.truncated) TRUNCATION_NOTICE else MALFORMED_NOTICE)
            step = parseStep(res.text)
        }
        if (!res.truncated || step is AgentStep.ToolRequest) return withSources(step, res.sources)
        return withSources(truncatedStep(res.text), res.sources)
    }

    /** Veb-qidiruvli javobning oxiriga manbalar ro'yxati qo'shiladi (faqat oddiy matnli javobga, tool-call'ga emas). */
    private fun withSources(step: AgentStep, sources: List<WebSource>): AgentStep {
        if (sources.isEmpty() || step !is AgentStep.AssistantText) return step
        val list = sources.joinToString("\n") { "- [${it.title.replace('[', '(').replace(']', ')')}](${it.url})" }
        return AgentStep.AssistantText(step.text + "\n\n" + tr("Manbalar") + ":\n" + list)
    }

    /** Matnda tool-call boshlangan (`"action": "write_file"` kabi), lekin JSON to'liq emas. */
    private fun isCutToolCall(raw: String): Boolean = agentCapable && TOOL_ACTION_REGEX.containsMatchIn(raw)

    /**
     * Model tool-call yozmoqchi bo'lgan, lekin JSON buzuq (masalan `{"action", "write_file", "path", ...}` —
     * ikki nuqta o'rniga vergul). parseStep ham, [repairFlatToolCall] ham uni o'qiy olmagan.
     */
    private fun isMalformedToolCall(raw: String): Boolean = agentCapable && LOOSE_ACTION_REGEX.containsMatchIn(raw)

    /** Uzilgan javob: ko'rinadigan matn (yarim JSON'siz) + ogohlantirish. */
    private fun truncatedStep(raw: String): AgentStep {
        val visible = raw.replace(THINK_BLOCK_REGEX, "").trim()
        val m = if (agentCapable) TOOL_ACTION_REGEX.find(visible) else null
        var head = if (m != null) {
            val brace = visible.lastIndexOf('{', m.range.first)
            if (brace >= 0) visible.substring(0, brace) else visible.substring(0, m.range.first)
        } else visible
        head = head.replace(Regex("```[a-zA-Z]*\\s*$"), "").trim()
        val warning = tr("⚠ Javob uzilib qoldi: model chiqish limitiga yetdi. Vazifani kichikroq bo'laklarga bo'lib so'rang yoki «davom et» deb yozing.")
        return AgentStep.AssistantText(if (head.isEmpty()) warning else head + "\n\n" + warning)
    }

    /** Onlayn model uchun tizim ko'rsatmasi: agent bo'lsa — asboblar bilan, aks holda oddiy suhbat. */
    private fun onlineSystem(): String =
        if (agentCapable) com.mobagent.app.prompt.onlineAgentSystemPrompt(linuxMode) else ONLINE_SYSTEM_PROMPT

    /**
     * ODDIY (ilgarigi) versiya `json.decodeFromString<ToolCall>(raw.trim())`ni
     * to'g'ridan-to'g'ri BUTUN javobga qo'llardi — ya'ni JSON'dan oldin yoki
     * keyin BITTA ORTIQCHA belgi (masalan "Albatta! " yoki ```code fence```)
     * bo'lsa ham parse butunlay muvaffaqiyatsiz tugab, tool-call HECH QACHON
     * bajarilmasdi (model xato JSON qaytargan deb emas, balki "oddiy matn"
     * deb noto'g'ri talqin qilinardi). System prompt (ModelPromptStrategy)
     * qanchalik qat'iy yozilmasin, 1.5B-7B kabi kichik modellar vaqti-vaqti
     * bilan baribir shu ikki xil xatoni qiladi:
     *   1) DeepSeek-R1 kabi "reasoning" modellar JSON'dan OLDIN <think>...</think>
     *      blokini qo'shib yuborishi mumkin (ChatMlPromptStrategy'dagi
     *      extraHint bunga qarshi ko'rsatma bersa ham, kafolat emas).
     *   2) Model JSON'ni ```json ... ``` kabi markdown kod-blokka o'rashi
     *      yoki oldiga/keyiniga qisqa izoh matni qo'shishi mumkin.
     * Shuning uchun bu yerda avval <think> bloki olib tashlanadi, so'ng
     * qolgan matndan birinchi TENGLASHGAN {...} JSON obyekti ajratib
     * olinadi (kod-fence bo'lsa — undan ham) — shundan keyingina qat'iy
     * decodeFromString bilan tasdiqlanadi. Bu — modelni "kechirish" emas,
     * balki uning xatosi tufayli xavfsiz (lekin bajarilishi kerak bo'lgan)
     * tool-call yo'qolib ketmasligini ta'minlash. Agar ajratib olingan
     * matn baribir yaroqli JSON bo'lmasa (yoki umuman JSON bo'lmasa —
     * haqiqiy yakuniy javob bo'lgani uchun), oddiy AssistantText'ga
     * qaytiladi — lekin foydalanuvchiga <think> ichki mulohaza HECH QACHON
     * ko'rsatilmasin deb, o'sha yerda ham u tozalangan holda beriladi.
     */
    private fun parseStep(raw: String): AgentStep {
        val withoutThink = raw.replace(THINK_BLOCK_REGEX, "").trim()
        if (!agentCapable) {
            // Chat rejimida hech qachon JSON parse qilinmaydi — model
            // tasodifan JSON'ga o'xshash narsa yozsa ham, oddiy matn
            // sifatida ko'rsatiladi (buyruq HECH QACHON ishga tushmaydi).
            return AgentStep.AssistantText(withoutThink.ifBlank { raw.trim() })
        }
        // Avval kod-fence'siz (butun matn bo'yicha), keyin fence ichidan urinamiz: fayl matni ichida
        // ``` bo'lsa (masalan README yozilganda) fence-regex JSON'ni o'rtasidan qirqib yuborardi.
        val candidates = listOf(
            extractJsonCandidate(withoutThink, useFence = false),
            extractJsonCandidate(withoutThink, useFence = true)
        ).distinct()
        for (candidate in candidates) {
            val parsed = try {
                json.decodeFromString<ToolCall>(candidate)
            } catch (e: Exception) {
                // Ba'zi modellar (masalan Qwen) kalit va qiymat orasiga ":" o'rniga "," qo'yadi:
                // {"action", "write_file", "path", "/root/a.html", ...}. Mazmuni aniq bo'lsa, tuzatamiz.
                val fixed = repairFlatToolCall(candidate) ?: continue
                try {
                    json.decodeFromString<ToolCall>(fixed)
                } catch (e2: Exception) {
                    continue
                }
            }
            // Noma'lum "action" — bu tool-call emas (masalan javob ichidagi JSON namunasi).
            if (parsed.action !in KNOWN_ACTIONS) continue
            // Tasdiqlash dialogida foydalanuvchi HAQIQATDA bajariladigan
            // buyruqni ko'rishi uchun, yo'llar shu yerdayoq tuzatiladi.
            val call = parsed.copy(
                cmd = parsed.cmd?.let { normalizeStoragePaths(it) },
                path = parsed.path?.let { normalizeStoragePaths(it) },
                to = parsed.to?.let { normalizeStoragePaths(it) }
            )
            val at = withoutThink.indexOf(candidate)
            val preamble = if (at > 0) {
                withoutThink.substring(0, at).replace(Regex("```[a-zA-Z]*\\s*$"), "").trim()
            } else ""
            return AgentStep.ToolRequest(call, candidate, preamble)
        }
        return AgentStep.AssistantText(withoutThink.ifBlank { raw.trim() })
    }

    companion object {
        // Onlayn modellar katta kontekstli, shuning uchun tarix limiti lokal modeldan ancha keng.
        private const val ONLINE_HISTORY_CHARS = 120_000
        private val KNOWN_ACTIONS = setOf(
            "execute_bash", "write_file", "edit_file", "list_dir", "read_file", "delete", "rename",
            "web_search", "web_fetch"
        )

        // Chiqish limitida uzilgan tool-call uchun avtomatik qayta urinishlar soni.
        private const val MAX_TRUNCATION_RETRIES = 2

        // "action" dan keyin ":" yoki "," — buzuq tool-call'ni ham ushlaydi.
        private val LOOSE_ACTION_REGEX = Regex("\"action\"\\s*[,:]\\s*\"(?:" + KNOWN_ACTIONS.joinToString("|") + ")\"")

        private val TOOL_KEYS = setOf("action", "cmd", "path", "content", "find", "replace", "to", "query", "url")

        private const val MALFORMED_NOTICE =
            "\n\n[SYSTEM NOTICE] Your previous reply contained a tool call with INVALID JSON (keys and values were separated " +
                "by commas instead of colons), so it was not run. Write it again as a valid JSON object: " +
                "{\"action\": \"write_file\", \"path\": \"...\", \"content\": \"...\"} — every key is followed by a colon. " +
                "Reply with ONE short sentence and the tool-call JSON."

        /**
         * `{"action", "write_file", "path", "/root/a.html", "content", "..."}` kabi buzuq obyektni — faqat satrlardan
         * iborat, kalit/qiymat juft bo'lib kelsa — to'g'ri `{"action": "...", ...}` ko'rinishiga keltiradi.
         * Hech narsani taxmin qilmaydi: satrdan boshqa narsa (raqam, ichki obyekt), noma'lum kalit yoki toq
         * sondagi satr bo'lsa null qaytaradi (u holda modelga qayta so'raladi).
         */
        internal fun repairFlatToolCall(candidate: String): String? {
            val t = candidate.trim()
            if (!t.startsWith("{") || !t.endsWith("}")) return null
            val strings = ArrayList<String>()
            var i = 1
            val end = t.length - 1
            while (i < end) {
                val c = t[i]
                when {
                    c == ',' || c == ':' || c.isWhitespace() -> i++
                    c == '"' -> {
                        var j = i + 1
                        while (j < end && t[j] != '"') j += if (t[j] == '\\') 2 else 1
                        if (j >= end) return null
                        strings.add(t.substring(i + 1, j))
                        i = j + 1
                    }
                    else -> return null
                }
            }
            if (strings.size < 2 || strings.size % 2 != 0 || strings[0] != "action") return null
            val sb = StringBuilder("{")
            for (k in strings.indices step 2) {
                if (strings[k] !in TOOL_KEYS) return null
                if (k > 0) sb.append(',')
                sb.append('"').append(strings[k]).append("\":\"").append(strings[k + 1]).append('"')
            }
            return sb.append('}').toString()
        }

        private val TOOL_ACTION_REGEX = Regex("\"action\"\\s*:\\s*\"(?:" + KNOWN_ACTIONS.joinToString("|") + ")\"")

        private const val TRUNCATION_NOTICE =
            "\n\n[SYSTEM NOTICE] Your previous reply was cut off by the output length limit, so its tool call was lost. " +
                "Do NOT write a large file in a single write_file. First send write_file with a short skeleton " +
                "(under 60 lines, with placeholder comments), then add the parts one at a time with edit_file. " +
                "Reply with ONE short sentence and the tool-call JSON."

        // Oddiy suhbat (agent emas) uchun.
        private const val ONLINE_SYSTEM_PROMPT =
            "You are MobAgent, a helpful AI assistant inside a mobile app. " +
                "Always reply in the same language the user writes in. " +
                "Be clear and concise; use Markdown (lists, code blocks) when it helps."

        private val THINK_BLOCK_REGEX = Regex("(?s)<think>.*?</think>")
        private val CODE_FENCE_REGEX = Regex("(?s)```(?:json)?\\s*(.*?)\\s*```")

        /**
         * Matndan bitta JSON obyektini ajratib oladi — avval markdown
         * kod-fence bo'lsa uni yechadi, so'ng birinchi "{" belgisidan
         * boshlab qavslarni sanab (depth counting), unga mos yopiluvchi "}"
         * gacha bo'lgan qismni qaytaradi. Bu to'liq JSON-parser EMAS (faqat
         * qavs balansini hisoblaydi, satr ichidagi "{" ni alohida ajratmaydi)
         * — lekin bu yetarli, chunki yakuniy tasdiq baribir yuqoridagi qat'iy
         * `decodeFromString` orqali bo'ladi: agar ajratilgan bo'lak yaroqsiz
         * bo'lsa, xavfsiz tarzda oddiy matn sifatida talqin qilinadi.
         */
        private fun extractJsonCandidate(text: String, useFence: Boolean): String {
            val unfenced = if (useFence) {
                CODE_FENCE_REGEX.find(text)?.groupValues?.get(1)?.trim() ?: text
            } else text

            val start = unfenced.indexOf('{')
            if (start == -1) return unfenced

            // Qavslar sanalganda qator ("...") ichidagi { } e'tiborga olinmaydi — onlayn modellar
            // write_file bilan kod (CSS/JS/JSON) yozganda "content" ichida juft bo'lmagan { } uchraydi.
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until unfenced.length) {
                val c = unfenced[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return unfenced.substring(start, i + 1)
                    }
                }
            }
            return unfenced
        }
    }

    /** Foydalanuvchi "Ha" degandan keyin chaqiriladi. */
    suspend fun executeApprovedTool(call: ToolCall): String {
        return when (call.action) {
            "execute_bash" -> {
                val cmd = call.cmd?.takeIf { it.isNotBlank() } ?: return "Natija (kod 1):\nXato: buyruq bo'sh. Provide a non-empty cmd, or use list_dir/read_file."
                val result = executor.runShell(cmd)
                "Natija (kod ${result.exitCode}):\n${result.output}"
            }
            "write_file" -> {
                val path = call.path ?: return "Natija (kod 1):\nXato: path bo'sh"
                val r = executor.writeFile(path, call.content ?: return "Natija (kod 1):\nXato: content bo'sh")
                "Natija (kod ${r.exitCode}):\n${r.output}"
            }
            "edit_file" -> {
                val path = call.path ?: return "Natija (kod 1):\nXato: path bo'sh"
                val r = executor.editFile(path, call.find.orEmpty(), call.replace.orEmpty())
                "Natija (kod ${r.exitCode}):\n${r.output}"
            }
            "list_dir" -> {
                val r = executor.listDir(call.path ?: return "Natija (kod 1):\nXato: path bo'sh")
                "Natija (kod ${r.exitCode}):\n${r.output}"
            }
            "read_file" -> {
                val r = executor.readFile(call.path ?: return "Natija (kod 1):\nXato: path bo'sh")
                "Natija (kod ${r.exitCode}):\n${r.output}"
            }
            "delete" -> {
                val r = executor.deletePath(call.path ?: return "Natija (kod 1):\nXato: path bo'sh")
                "Natija (kod ${r.exitCode}):\n${r.output}"
            }
            "rename" -> {
                val path = call.path ?: return "Natija (kod 1):\nXato: path bo'sh"
                val r = executor.renamePath(path, call.to ?: return "Natija (kod 1):\nXato: to bo'sh")
                "Natija (kod ${r.exitCode}):\n${r.output}"
            }
            "web_search" -> {
                val r = withContext(Dispatchers.IO) { WebTools.search(call.query ?: call.cmd.orEmpty()) }
                "Natija (kod ${r.exitCode}):\n${r.output}"
            }
            "web_fetch" -> {
                val r = withContext(Dispatchers.IO) { WebTools.fetch(call.url ?: call.path.orEmpty()) }
                "Natija (kod ${r.exitCode}):\n${r.output}"
            }
            else -> "Noma'lum action: ${call.action}"
        }
    }

    /**
     * Terminal natijasidan keyin chaqiriladi. Ilgari bu funksiya doim
     * oddiy matn (String) qaytarardi, shuning uchun bitta so'rovga bitta
     * buyruq bajarilib, keyin agent to'xtab qolardi. Endi model natijani
     * ko'rib, vazifa hali tugamagan bo'lsa, YANA bitta tool-call (JSON)
     * so'rashi mumkin — AgentStep.ToolRequest sifatida qaytadi va UI
     * qatlami (ChatScreen) uni yana Security Guard Dialog orqali
     * tasdiqlashga qo'yadi (xavfsizlik hech qachon chetlab o'tilmaydi,
     * faqat foydalanuvchi qayta savol yozishi shart emas).
     */
    suspend fun continueAfterTool(userPrompt: String, toolResult: String, history: List<StoredMessage> = emptyList(), onToken: (String) -> Unit = {}): AgentStep {
        // Onlayn model: oxirgi natija `history` ichida allaqachon bor ([TERMINAL OUTPUT] user-xabari
        // sifatida, OnlineApi.buildTurns) — shuning uchun bu yerda faqat keyingi qadam ko'rsatmasi beriladi.
        online?.let { session ->
            val nudge = "Continue the task for the user's request: \"$userPrompt\". " +
                "Look at the latest [TERMINAL OUTPUT] above. " +
                "If the result says Error or WARNING, do NOT report success: reply with ONE short sentence and then " +
                "the tool-call JSON that fixes it (read the file first if unsure). " +
                "If another command is truly still needed to finish the task, reply with ONE short sentence and then " +
                "the tool-call JSON. Otherwise write a SHORT final answer (1-2 sentences, same language as the user's " +
                "request) with no JSON. Never invent output."
            val trimmed = trimHistory(history, ONLINE_HISTORY_CHARS)
            val first = OnlineApi.streamChatEx(session, onlineSystem(), trimmed, nudge, onToken)
            return resolveOnline(first) { notice ->
                OnlineApi.streamChatEx(session, onlineSystem(), trimmed, nudge + notice, {})
            }
        }
        // MUHIM TUZATISH: ilgari "SO'ZMA-SO'Z QAYTA YOZMA" (taqiqlovchi, salbiy
        // shaklda) ko'rsatma berilardi. Kichik model buni tushunmay, ko'pincha
        // ko'rsatmaning o'zini (yoki uning mazmunini) javob deb chiqarib
        // yuborardi — masalan "So'zma-so'z qayta yozilmadi" degan mazmunsiz
        // xabar. Endi ko'rsatma: (a) ijobiy shaklda, aniq NIMA qilish kerakligi
        // aytiladi (taqiqlangan narsa emas), (b) namuna javob bilan ko'rsatiladi,
        // (c) hech qanday prefiks/format so'ralmaydi. Terminal chiqishi ham
        // [TERMINAL OUTPUT] deb aniq belgilanadi — buni foydalanuvchining o'zi
        // yozgan deb yoki modelning oldingi gapi deb aralashtirmasligi uchun.
        val followUp = "[TERMINAL OUTPUT for: $userPrompt]\n${toolResult.toEnglishForModel()}\n\n" +
            "Write ONE short natural-language reply (1-2 sentences, same language as " +
            "the user's request above) that tells the user what happened. " +
            "Example reply: \"Done — the file was created.\" " +
            "Do not add any prefix, do not repeat the raw output above. " +
            "If the result above says Error or WARNING, do NOT report success: reply with " +
            "ONE short sentence and then the tool-call JSON that fixes it (read the file first if unsure). " +
            "Otherwise, only if another command is truly still needed to finish the task, reply with " +
            "ONE short sentence and then the tool-call JSON on a new line instead."
        val fullPrompt = promptStrategy.buildPrompt(trimHistory(history, maxHistoryChars), followUp)
        val sb = StringBuilder()
        engine.generateStream(fullPrompt, activeGrammar).collect { tok ->
            sb.append(tok)
            onToken(tok)
        }
        return parseStep(sb.toString())
    }
}
