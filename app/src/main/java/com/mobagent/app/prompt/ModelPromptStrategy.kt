package com.mobagent.app.prompt

import android.content.Context
import com.mobagent.app.history.StoredMessage
import com.mobagent.app.linux.LinuxEnvironment
import com.mobagent.app.model.ModelRole
import kotlinx.serialization.Serializable

/**
 * Model oilasi — har birining o'z chat-shabloni (maxsus teglar) va
 * o'z "tushunish quvvati"ga mos tizim ko'rsatmasi bor. Kichik oflayn
 * modellar (1.5B-3B) uzun/murakkab promptni ko'tara olmaydi, shuning
 * uchun har biriga alohida, qisqa va bitta JSON namunali strategiya
 * yozilgan (Strategy Pattern).
 */
@Serializable
enum class ModelFamily {
    QWEN,          // ChatML: <|im_start|>...<|im_end|>
    DEEPSEEK_R1,   // Qwen tokenizerida o'qitilgan, ChatML'ni tushunadi
    LLAMA3,        // <|start_header_id|>...<|end_header_id|>...<|eot_id|>
    GEMMA,         // <start_of_turn>...<end_of_turn> (system roli yo'q)
    PHI3,          // <|system|>...<|end|><|user|>...<|end|><|assistant|>...<|end|>
    MISTRAL,       // [INST] ... [/INST] (system roli yo'q)
    GENERIC        // Noma'lum/oddiy model — oddiy matn shablon
}

/**
 * Ilova ichida saqlanadigan JSON ko'rinishi barcha model oilalari uchun
 * BITTA xil ({"action":"execute_bash","cmd":"..."}) — buni
 * AgentOrchestrator/CommandExecutor allaqachon parse qiladi. Har model
 * oilasi farqlanadigan joyi — shu JSON'ni QANDAY o'rab, qanday chat
 * teglari bilan so'rash: shu yerda hal qilinadi.
 */
interface ModelPromptStrategy {
    /** Qat'iy, xato-toleranti past qoldiradigan tizim ko'rsatmasi (~300-400 token). */
    fun systemPrompt(): String

    /**
     * To'liq promptni shu model oilasining o'z chat-shabloniga moslab yig'adi.
     * @param history faqat tarix (tizim ko'rsatmasisiz, joriy so'rovsiz), eng eskisi boshida.
     */
    fun buildPrompt(history: List<StoredMessage>, userPrompt: String): String

    /**
     * Faqat tizim ko'rsatmasini o'z ichiga olgan, TARIX VA FOYDALANUVCHI
     * SO'ROVISIZ prefiks — shu (model oilasi, systemPrompt matni)
     * juftligi uchun HAR doim bir xil bo'ladigan qism. LlamaEngine bu
     * prefiksni bir marta decode qilib, KV-kesh holatini diskka saqlab
     * qo'yadi (warmup) — shundan keyin HAR YANGI suhbat system promptni
     * noldan qayta hisoblamasdan, saqlangan holatdan boshlanadi.
     *
     * Gemma'da alohida "system" roli yo'q — tizim ko'rsatmasi har doim
     * BIRINCHI user burilishi bilan BIRGA ("system\n\nfoydalanuvchi matni")
     * bitta blok sifatida yuboriladi, shuning uchun uning haqiqiy
     * "faqat tizim" prefiksi mavjud emas. Bunday holatda null qaytariladi
     * va warmup shu strategiya uchun jimgina o'tkazib yuboriladi (xato
     * emas — faqat bu bitta optimallashtirish Gemma'ga tegishli emas).
     */
    fun systemOnlyPrefix(): String?
}

// MUHIM (tezlik + ishonchlilik): bu qat'iy ko'rsatmalar ENDI inglizcha
// yozilgan. Sabab ikkita:
// 1) KV-kesh qayta ishlatilsa ham, HAR bir yangi chat sessiyasining birinchi
//    xabari bu matnni to'liq (keshsiz) qayta ishlaydi — ko'pchilik ochiq
//    modellar tokenizatori inglizcha matnni o'zbekchaga qaraganda kamroq
//    token bilan kodlaydi, demak birinchi javob birozgina tezroq chiqadi.
// 2) 1.5B-3B kabi kichik modellar instruction-tuning ma'lumotining katta
//    qismi inglizcha bo'lgani uchun qat'iy JSON formatini inglizcha
//    ko'rsatmada ancha barqarorroq bajaradi — bu ham bilvosita tezlikka
//    yordam beradi (noto'g'ri formatlangan javob → qayta so'rash kamayadi).
// Foydalanuvchiga ko'rinadigan YAKUNIY javob baribir o'zbek tilida bo'lishi
// SHART qilib qoldirilgan (pastdagi JSON_RULE'ning oxirgi qatoriga qarang).
//
// KUCHAYTIRILGAN VERSIYA: ilgari IDENTITY faqat "men Termux emasman" deb
// tushuntirardi — lekin ko'pchilik bazaviy (instruct-tuned) modellar RLHF
// davomida "men fayllarga kira olmayman / buyruq bajara olmayman" deb
// javob berishga o'rgatilgan bo'ladi. Shu odat MobAgent'da noto'g'ri —
// model HAQIQATDA pastdagi JSON tool-call orqali qurilmada buyruq
// bajara oladi — shuning uchun bu qobiliyat endi ANIQ tasdiqlanadi, aks
// holda kichik model vazifani bajarish o'rniga "kechirasiz, men buni
// qila olmayman" deb rad etishi mumkin (kuzatilgan real xato turi).
private const val IDENTITY =
    "You are MobAgent, a helpful assistant inside an Android phone app. " +
    "You can control the phone's shell by outputting a JSON tool-call (format below). " +
    "Use it ONLY when the user asks you to do something on the phone. " +
    "For greetings, small talk and general questions just reply in plain text, with NO command. " +
    "You are not Termux."

// KUCHAYTIRILGAN VERSIYA: bitta ijobiy namunadan tashqari endi bitta ANIQ
// "noto'g'ri" namuna ham bor. Kichik (1.5B-7B) modellar uchun kontrastli
// (to'g'ri + noto'g'ri) namuna, faqat bitta ijobiy namunaga qaraganda,
// qat'iy formatni ancha ishonchli saqlaydi — chunki eng ko'p uchraydigan
// xato aynan shu "noto'g'ri" namunadagi ikkita xatti-harakat: (1) JSON'dan
// oldin/keyin izoh matni qo'shish, (2) JSON'ni ```code fence``` ichiga
// o'rash. Ikkalasi ham AgentOrchestrator'da JSON parse'ni buzadi.
// TUZATISH (haqiqiy xato): ilgari namunada "ls ~/storage/shared/DCIM" edi —
// bu Termux'ning yo'li. MobAgent'da "~" ilovaning shaxsiy papkasi, u yerda
// storage/shared degan papka YO'Q, shuning uchun model namunani nusxalab
// "index.html" yaratmoqchi bo'lganda "No such file or directory" xatosi
// chiqdi. Endi telefon xotirasining HAQIQIY yo'li (/sdcard) beriladi va
// model mutlaq yo'llardan foydalanishi aytiladi.
private const val PATHS_PHONE = """PATHS: phone storage is /sdcard (e.g. /sdcard/Download, /sdcard/DCIM, /sdcard/Documents). ALWAYS use absolute paths starting with /sdcard. NEVER use ~ or ${'$'}HOME — they do not exist here."""
 
// LINUX faol bo'lsa: standart joy — Linux uyi (/root). Telefon xotirasi (/sdcard) FAQAT foydalanuvchi
// "telefon / qurilma / yuklamalar / galereya / /sdcard" desa. Ilgari standart doim /sdcard/Download
// edi, shuning uchun "fayl yarat" desa fayl Linuxda emas, Download'da paydo bo'lardi.
private const val PATHS_LINUX = """PATHS: you work inside a Linux (Ubuntu) environment. The DEFAULT place for everything you create is the Linux home /root (for example /root/index.html or /root/notes.txt). ALWAYS use absolute paths starting with /root. NEVER use ~ or ${'$'}HOME. Use phone storage (/sdcard, e.g. /sdcard/Download) ONLY when the user explicitly mentions the phone, device, downloads, gallery or /sdcard."""

private const val EXAMPLES_TPL = """Correct — user: "hello" → plain text, NO command:
Hello! How can I help you?

Correct — user: "how are you?" → plain text, NO command:
I'm doing well, thanks! What would you like me to do?

Correct — user: "show my pictures" → one short sentence, then the JSON:
Listing your pictures.
{"action": "list_dir", "path": "/sdcard/DCIM"}

Correct — user: "create index.html" → one short sentence, then the JSON:
Creating index.html.
{"action": "write_file", "path": "@DIR@/index.html", "content": "<!DOCTYPE html>\n<html>\n<body>\n<h1>Hello</h1>\n</body>\n</html>\n"}

Correct — user: "change the heading to Hi in index.html" → one short sentence, then the JSON:
Changing the heading.
{"action": "edit_file", "path": "@DIR@/index.html", "find": "<h1>Hello</h1>", "replace": "<h1>Hi</h1>"}

Correct — user: "read index.html" → one short sentence, then the JSON:
Reading index.html.
{"action": "read_file", "path": "@DIR@/index.html"}

Correct — user: "delete old.txt" → one short sentence, then the JSON:
Deleting old.txt.
{"action": "delete", "path": "@DIR@/old.txt"}

Correct — user: "rename a.txt to b.txt" → one short sentence, then the JSON:
Renaming the file.
{"action": "rename", "path": "@DIR@/a.txt", "to": "@DIR@/b.txt"}

Wrong — never do any of these:
- Running a command for a greeting or a normal question.
- Any text AFTER the JSON, or more than one sentence before it.
- Wrapping the JSON in ```code fences``` or writing the command as a code block instead of the JSON.
- More than one JSON object in one reply.
- Using ls or cat to look at files (use list_dir / read_file).
- Using printf, echo, cat > or sed to create or edit files (use write_file / edit_file).
- Paths like ~/storage/shared/DCIM or ${'$'}HOME/...  (they do not exist)."""

/** Yo'llar qoidasi + namunalar. Linux faol bo'lsa namunalar /root'da, aks holda /sdcard/Download'da. */
private fun oneShotHint(linux: LinuxPromptMode): String =
    if (linux == LinuxPromptMode.NONE) PATHS_PHONE + "\n\n" + EXAMPLES_TPL.replace("@DIR@", "/sdcard/Download")
    else PATHS_LINUX + "\n\n" + EXAMPLES_TPL.replace("@DIR@", "/root")


// Qurilma imkoniyatlari (batareya, clipboard, kamera, bildirishnoma) haqidagi
// ko'rsatma ILGARI ONE_SHOT_HINT ichida, HAR doim yuboriladigan promptda
// edi — bu 1.5B-3B kabi kichik modellarni chalkashtirib, ular hatto o'zini
// "Termux" deb hisoblab qolishiga sabab bo'lgan (kuzatilgan real xato).
// Bu buyruqlar kamdan-kam kerak bo'lgani uchun ENDI faqat MODEL SO'RASA
// (masalan model o'zi "batareya" so'zini javobga yozsa) emas, balki umuman
// tizim ko'rsatmasiga qo'shilmaydi — CommandExecutor baribir bu buyruqlarni
// tushunadi, model ularni faqat o'z (training) bilimidan kelib chiqib to'g'ri
// nom bilan chaqirsa ham ishlaydi. Zarurat bo'lsa kelajakda alohida,
// ixtiyoriy qo'shimcha sifatida qaytarish mumkin.
//
// KUCHAYTIRILGAN VERSIYA: qoidalar endi raqamlangan, bitta gap = bitta
// aniq qoida qilib bo'lingan (ilgari 3 ta uzun gap edi). Kichik modellar
// uchun qisqa, raqamlangan, imperativ qoidalar — uzun paragrafga qaraganda —
// ancha barqaror bajariladi (har qoida alohida "diqqat markazi" bo'ladi).
private const val JSON_RULE = """OUTPUT FORMAT — follow exactly:
1. LANGUAGE: always reply in the SAME language as the user's latest message (English → English, Uzbek → Uzbek, Russian → Russian). Never switch language on your own.
2. If the user asks for something that needs a phone command: write ONE short sentence (max 15 words, in the user's language) saying what you are about to do, then a new line, then ONE JSON object with no code block, no markdown, and no extra text. Example: "Reading the file." then {"action":"read_file","path":"/sdcard/Download/file.txt"}
3. If the user only greets you, chats, or asks a question that needs no command: reply in plain text, NO JSON.
4. Double quotes only. Escape any " inside a JSON string as \". No trailing commas, no extra keys.
5. After you see the terminal result, output ONE more short sentence + JSON only if another step is still needed. Never invent or guess a command's output. Only mention file names that literally appear in the terminal result; if the result is empty or an error, say so honestly instead of guessing. list_dir shows the NEWEST files first with their modified date, so the latest file is the first line.
6. When the task is done: give a SHORT final answer (1-2 sentences) in plain text, in the user's language, with no JSON. Do not copy the terminal output."""
 
// CHAT REJIMI (1B-2B modellar uchun): kichik modellar tool-call JSON
// formatini ishonchli bajara olmaydi — noto'g'ri formatlangan JSON, adashgan
// buyruqlar yoki "men buni bajardim" deb yolg'on da'vo qilish kabi xatolar
// kuzatilgan. Shuning uchun ular uchun IDENTITY/JSON_RULE/ONE_SHOT_HINT
// umuman yuborilmaydi — bu modellar shell/fayllar haqida bilishi ham shart
// emas. AgentOrchestrator.parseStep bu rejimda hech qachon JSON'ni tool-call
// sifatida ishga tushirmaydi (qo'shimcha xavfsizlik qatlami sifatida) —
// hatto model tasodifan JSON o'xshash narsa yozsa ham, oddiy matn sifatida
// ko'rsatiladi.
private const val CHAT_ONLY_SYSTEM =
    "You are MobAgent, a friendly assistant running inside an Android phone app. " +
    "You do not have access to the device's shell, files, or any commands in this mode — " +
    "you can only chat. Never claim to run commands, read files, or access the phone; " +
    "if asked to do so, explain briefly that this model is chat-only and a larger " +
    "(3B+) model is needed for that. Always reply in the same language as the user's latest message."

// TEZKOR (<=1B) modellar uchun YO'NALISH promptlari. Qoidalar:
//  - faqat inglizcha, o'zbekcha so'z yoki namuna YO'Q (kichik model tilni aralashtirib yuboradi);
//  - har biri bitta vazifa, ~60-80 token, bitta-ikkita qisqa qoida;
//  - javob tili: doim inglizcha (kichik modellar o'zbekchani yomon yozadi);
//  - buyruq/fayl haqida faqat "qila olmaysan" deyiladi (bular Chat rejimida).
private const val NO_TOOLS = "You cannot run commands or open files; never claim you did."

private const val ROLE_QUICK_ANSWERS =
    "You are MobAgent, a fast assistant on an Android phone. Always reply in English. " +
    "Answer in 1-3 short sentences, with the answer first and no preamble. " +
    "If you are not sure, say \"I'm not sure.\" Never make up facts, names or numbers. " + NO_TOOLS

private const val ROLE_CODE_HELPER =
    "You are MobAgent's coding helper on an Android phone. Always reply in English. " +
    "For a code request: give the shortest working code in ONE code block, then at most 2 sentences of explanation. " +
    "For an error message: state the cause in one sentence, then the fix. " +
    "Prefer simple, common solutions. If the request is not about code, answer briefly in plain text. " + NO_TOOLS

private const val ROLE_TEXT_WRITER =
    "You are MobAgent's writing helper on an Android phone. Always reply in English. " +
    "Write or rewrite the text the user asks for: emails, posts, messages, summaries. Keep it short and clear. " +
    "Output only the finished text, with no introduction and no comments. " +
    "If details are missing, write a sensible short version instead of asking questions. " + NO_TOOLS

private const val ROLE_MATH_LOGIC =
    "You are MobAgent's reasoning helper on an Android phone. Always reply in English. " +
    "Solve the problem in short numbered steps, then write \"Answer:\" followed by the final result on its own line. " +
    "Check the arithmetic once before answering. Keep your thinking brief. " + NO_TOOLS

/** Chat rejimi (agent emas) uchun system prompt: yo'nalish bor bo'lsa — o'shanga mos, bo'lmasa umumiy. */
private fun chatSystem(role: ModelRole?): String = when (role) {
    ModelRole.QUICK_ANSWERS -> ROLE_QUICK_ANSWERS
    ModelRole.CODE_HELPER -> ROLE_CODE_HELPER
    ModelRole.TEXT_WRITER -> ROLE_TEXT_WRITER
    ModelRole.MATH_LOGIC -> ROLE_MATH_LOGIC
    null -> CHAT_ONLY_SYSTEM
}


/**
 * Agent buyruqlari qaysi muhitda bajarilishi. Modelga shu haqda aytilmasa, u
 * Android shell deb o'ylab, `apt`/`python` yo'qligidan shikoyat qiladi yoki
 * fayl asboblari bilan shell bir xil fayllarni ko'radi deb adashadi.
 *  NONE     — eski (toybox/Termux) shell: hech qanday qo'shimcha ko'rsatma yo'q.
 *  SHARED   — Ubuntu (proot), telefon xotirasi /sdcard sifatida ulangan.
 *  ISOLATED — Ubuntu (proot), telefon xotirasi Linux ichida KO'RINMAYDI.
 */
enum class LinuxPromptMode { NONE, SHARED, ISOLATED }

/** Hozirgi Linux holatidan prompt rejimi. Yengil (fayl/prefs tekshiruvi) — har recomposition'da chaqirish mumkin. */
fun linuxPromptMode(context: Context): LinuxPromptMode = when {
    !LinuxEnvironment.isActive(context) -> LinuxPromptMode.NONE
    LinuxEnvironment.isSdcardShared(context) -> LinuxPromptMode.SHARED
    else -> LinuxPromptMode.ISOLATED
}

// Linux ko'rsatmasi FAQAT to'liq (3B+) va onlayn agentga qo'shiladi. Ixcham (1.5B-2B)
// agentning grammatikasida execute_bash umuman yo'q (ToolGrammar.COMPACT), shuning uchun
// unga shell haqida gapirish foydasiz va uni chalkashtiradi.
// Qasddan "python3/git/gcc bor" DEMAYMIZ: minimal Ubuntu'da ular bo'lmasligi mumkin —
// model yetishmaganini apt bilan o'zi o'rnatadi.
private const val LINUX_BASE = """
LINUX: execute_bash runs inside a real Ubuntu Linux (you are root, there is no sudo). If a program is missing, install it with: apt-get update && apt-get install -y <package>. The shell session is persistent and the workdir starts at /root. Your job is to follow the user's command request. Use the phone's file tools for file operations and the shell only for commands the user explicitly asks you to run. Do not spend time on unnecessary setup, but do use Linux for tasks that truly require a shell.
"""

private const val LINUX_SHARED = """
Phone storage is also mounted at /sdcard inside Linux, so the shell can use phone files too (for example cd /sdcard/Download/app && python3 main.py). Use /sdcard only when the user means the phone storage or an attached file, otherwise keep work in /root.
"""

private const val LINUX_ISOLATED = """
The shell CANNOT see /sdcard (phone storage sharing is off), but the file tools can read and write /sdcard. Do not run shell commands on /sdcard paths. If the user wants the shell to work on phone files, tell them that the phone storage is not shared with the Linux environment.
"""

private fun linuxHint(mode: LinuxPromptMode): String = when (mode) {
    LinuxPromptMode.NONE -> ""
    LinuxPromptMode.SHARED -> LINUX_BASE + LINUX_SHARED
    LinuxPromptMode.ISOLATED -> LINUX_BASE + LINUX_ISOLATED
}

// IXCHAM PROMPT (1.5B-2B agent rejimi): ~120 token. Kichik model uzun
// qoidalar ro'yxatini ushlab tura olmaydi — bitta qisqa qoida, oltita asbob,
// to'rtta namuna. Grammatika (ToolGrammar.COMPACT) formatni o'zi kafolatlaydi,
// shuning uchun prompt "JSON'ni to'g'ri yoz" deb ko'p gapirishi shart emas;
// u faqat NIMA vaqt asbob kerakligini va yo'llarni o'rgatadi.
private const val COMPACT_AGENT_SYSTEM = """You are MobAgent, an assistant in an Android app. Reply in the same language as the user's last message. Never mix languages.
Chat or question: plain text only.
Task with files: one short sentence, a new line, then one JSON tool call.
Tools: list_dir(path), read_file(path), write_file(path, content), edit_file(path, find, replace), delete(path), rename(path, to).
Paths start with /sdcard (default folder /sdcard/Download). Newline inside content is \n.
Examples:
user: hello
Hello! How can I help you?
user: show my pictures
Listing pictures.
{"action": "list_dir", "path": "/sdcard/DCIM"}
user: create index.html
Creating index.html.
{"action": "write_file", "path": "/sdcard/Download/index.html", "content": "<h1>Hello</h1>\n"}
user: change Hello to Hi in index.html
Editing the file.
{"action": "edit_file", "path": "/sdcard/Download/index.html", "find": "Hello", "replace": "Hi"}
After a tool result: one short sentence, no JSON, only use file names that appear in the result (list_dir lists newest first; never invent names), unless it says Error or WARNING — then fix it with another tool call."""

// Internet: qidiruv va sahifa o'qish ilovaning O'ZIDA bajariladi (curl/Linux kerak emas).
// Ilgari model "internetdan qidir" deganda curl yozardi: curl o'rnatilmagan, Linux o'chiq yoki sayt
// (Cloudflare) curl'ni bot deb to'sardi. Endi aniq asbob bor va curl bilan qidirish taqiqlanadi.
private const val WEB_TOOLS_HINT = "INTERNET: to search the web or read a web page, use the built-in tools by default " +
    "(curl/wget may be missing or blocked). EXCEPTION: if the user explicitly asks for curl, wget or a shell command " +
    "(e.g. \"use curl\", \"curl bilan qidir\", \"curl buyrug'i bilan\"), obey the user and run exactly that with " +
    "{\"action\":\"execute_bash\",\"cmd\":\"curl -sL 'https://...'\"} instead of web_search/web_fetch. " +
    "URLs must be written exactly as https://host/path (colon after https, no commas, no doubled https). Search: {\"action\":\"web_search\",\"query\":\"short search words\"}. " +
    "Read a page: {\"action\":\"web_fetch\",\"url\":\"https://...\"}. For a question that needs current facts: first web_search, " +
    "then web_fetch the 1-2 most relevant result URLs if the snippets are not enough, then answer in the user's language and mention the source URLs. " +
    "If a search fails, retry once with different words; if it still fails, tell the user honestly instead of guessing. " +
    "Use the shell for downloading a file only when the user explicitly asks to save a file from a URL. "

/**
 * Onlayn (bulutdagi, kuchli) modellar uchun agent ko'rsatmasi. Oflayn 3B+ bilan bir xil JSON
 * tool-call protokoli — shuning uchun ChatScreen/AgentOrchestrator/CommandExecutor o'zgarishsiz
 * ishlaydi va har buyruq baribir foydalanuvchi tasdig'idan o'tadi.
 */
fun onlineAgentSystemPrompt(linux: LinuxPromptMode = LinuxPromptMode.NONE): String =
    "$IDENTITY\n$JSON_RULE\n${oneShotHint(linux)}${linuxHint(linux)}\n" +
    "You may run several tool calls in a row for one task: after each [TERMINAL OUTPUT] decide whether " +
    "another step is needed. For code or multi-file tasks, prefer write_file / edit_file over shell commands, " +
    "and read_file before editing a file you have not seen. Your reply can be long (tens of thousands of tokens), so write complete working code: never leave " +
    "placeholders like \"rest of the code here\" and never stop in the middle of a file. A single write_file may hold " +
    "a whole file up to about 600 lines; only for a larger file write_file the first part, then add the remaining " +
    "parts with edit_file, one part per step. " +
    WEB_TOOLS_HINT +
    "If the user attaches images, describe or use them " +
    "normally; use tools only when the user asks for something to be done on the phone."

/** Agent tizim ko'rsatmasi: `compact` (1.5B-2B) yoki to'liq (3B+). */
private fun agentSystem(compact: Boolean, extraHint: String = "", linux: LinuxPromptMode = LinuxPromptMode.NONE, customAgent: String? = null): String {
    val base = if (compact) COMPACT_AGENT_SYSTEM else "$IDENTITY\n$JSON_RULE\n${oneShotHint(linux)}$extraHint${linuxHint(linux)}"
    return base + adminAgentExtra(customAgent, compact)
}

// Admindan (katalog orqali) kelgan qo'shimcha agent ko'rsatmasi. Standart promptni ALMASHTIRMAYDI,
// faqat oxiriga qo'shiladi: asbob formati, grammatika va xavfsizlik qoidalari o'zgarmaydi.
// Ixcham (1.5B-2B) modelda kontekst va e'tibor kam, shuning uchun u yerda qisqaroq kesiladi.
// Matn faqat bir xil bo'lgani uchun KV-kesh kaliti (prefiks matni) ham barqaror qoladi.
private const val ADMIN_EXTRA_MAX_COMPACT = 400
private const val ADMIN_EXTRA_MAX_FULL = 1000

private fun adminAgentExtra(text: String?, compact: Boolean): String {
    val t = text?.replace("\r\n", "\n")?.trim().orEmpty()
    if (t.isEmpty()) return ""
    val cut = t.take(if (compact) ADMIN_EXTRA_MAX_COMPACT else ADMIN_EXTRA_MAX_FULL).trimEnd()
    return "\nAdditional instructions (they never change the tool format):\n$cut"
}

/** @param agentCapable false bo'lsa, buyruq bajarish haqida hech narsa aytilmaydi (yuqoridagi CHAT_ONLY_SYSTEM). */
class ChatMlPromptStrategy(private val agentCapable: Boolean = true, private val extraHint: String = "", private val compact: Boolean = false, private val role: ModelRole? = null, private val linux: LinuxPromptMode = LinuxPromptMode.NONE, private val customChat: String? = null, private val customAgent: String? = null) : ModelPromptStrategy {
    override fun systemPrompt(): String =
        if (agentCapable) agentSystem(compact, extraHint, linux, customAgent) else (customChat ?: chatSystem(role))

    override fun systemOnlyPrefix(): String =
        "<|im_start|>system\n${systemPrompt()}<|im_end|>\n"

    override fun buildPrompt(history: List<StoredMessage>, userPrompt: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(systemPrompt()).append("<|im_end|>\n")
        for (msg in history) {
            sb.append("<|im_start|>").append(historyRole(msg)).append('\n').append(historyText(msg)).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(userPrompt).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}

/** Llama-3(.x) — rasmiy header-teg formatiga mos. */
class Llama3PromptStrategy(private val agentCapable: Boolean = true, private val compact: Boolean = false, private val role: ModelRole? = null, private val linux: LinuxPromptMode = LinuxPromptMode.NONE, private val customChat: String? = null, private val customAgent: String? = null) : ModelPromptStrategy {
    override fun systemPrompt(): String =
        if (agentCapable) agentSystem(compact, linux = linux, customAgent = customAgent) else (customChat ?: chatSystem(role))

    private fun header(role: String) = "<|start_header_id|>$role<|end_header_id|>\n\n"

    override fun systemOnlyPrefix(): String =
        header("system") + systemPrompt() + "<|eot_id|>"

    override fun buildPrompt(history: List<StoredMessage>, userPrompt: String): String {
        val sb = StringBuilder()
        sb.append(header("system")).append(systemPrompt()).append("<|eot_id|>")
        for (msg in history) {
            sb.append(header(historyRole(msg))).append(historyText(msg)).append("<|eot_id|>")
        }
        sb.append(header("user")).append(userPrompt).append("<|eot_id|>")
        sb.append(header("assistant"))
        return sb.toString()
    }
}

/** Gemma — alohida "system" roli yo'q, shuning uchun ko'rsatma birinchi user burilishiga qo'shiladi. */
class GemmaPromptStrategy(private val agentCapable: Boolean = true, private val compact: Boolean = false, private val role: ModelRole? = null, private val linux: LinuxPromptMode = LinuxPromptMode.NONE, private val customChat: String? = null, private val customAgent: String? = null) : ModelPromptStrategy {
    override fun systemPrompt(): String =
        if (agentCapable) agentSystem(compact, linux = linux, customAgent = customAgent) else (customChat ?: chatSystem(role))

    // Yuqoridagi interfeys kdoc'ida tushuntirilgani kabi: Gemma'da system
    // ko'rsatmasi doim birinchi user xabari bilan bitta blokda yuboriladi —
    // shuning uchun mustaqil "faqat tizim" prefiksi yo'q.
    override fun systemOnlyPrefix(): String? = null

    override fun buildPrompt(history: List<StoredMessage>, userPrompt: String): String {
        val sb = StringBuilder()
        if (history.isEmpty()) {
            // Ko'rsatma to'g'ridan-to'g'ri birinchi (joriy) user burilishiga qo'shiladi.
            sb.append("<start_of_turn>user\n").append(systemPrompt()).append("\n\n").append(userPrompt).append("<end_of_turn>\n")
        } else {
            sb.append("<start_of_turn>user\n").append(systemPrompt()).append("\n\n").append(historyText(history.first())).append("<end_of_turn>\n")
            for ((index, msg) in history.withIndex()) {
                if (index == 0) continue
                val role = if (historyRole(msg) == "user") "user" else "model"
                sb.append("<start_of_turn>").append(role).append('\n').append(historyText(msg)).append("<end_of_turn>\n")
            }
            sb.append("<start_of_turn>user\n").append(userPrompt).append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}

/** Phi-3(.5) — rasmiy <|system|>/<|user|>/<|assistant|> teg formatiga mos. */
class Phi3PromptStrategy(private val agentCapable: Boolean = true, private val compact: Boolean = false, private val role: ModelRole? = null, private val linux: LinuxPromptMode = LinuxPromptMode.NONE, private val customChat: String? = null, private val customAgent: String? = null) : ModelPromptStrategy {
    override fun systemPrompt(): String =
        if (agentCapable) agentSystem(compact, linux = linux, customAgent = customAgent) else (customChat ?: chatSystem(role))

    override fun systemOnlyPrefix(): String =
        "<|system|>\n${systemPrompt()}<|end|>\n"

    override fun buildPrompt(history: List<StoredMessage>, userPrompt: String): String {
        val sb = StringBuilder()
        sb.append("<|system|>\n").append(systemPrompt()).append("<|end|>\n")
        for (msg in history) {
            sb.append("<|").append(historyRole(msg)).append("|>\n").append(historyText(msg)).append("<|end|>\n")
        }
        sb.append("<|user|>\n").append(userPrompt).append("<|end|>\n")
        sb.append("<|assistant|>\n")
        return sb.toString()
    }
}

/** Mistral (v0.3) — alohida "system" roli yo'q, [INST]...[/INST] shablon. */
class MistralPromptStrategy(private val agentCapable: Boolean = true, private val compact: Boolean = false, private val role: ModelRole? = null, private val linux: LinuxPromptMode = LinuxPromptMode.NONE, private val customChat: String? = null, private val customAgent: String? = null) : ModelPromptStrategy {
    override fun systemPrompt(): String =
        if (agentCapable) agentSystem(compact, linux = linux, customAgent = customAgent) else (customChat ?: chatSystem(role))

    // Gemma'dagi kabi: mustaqil "faqat tizim" prefiksi yo'q — ko'rsatma doim
    // birinchi user burilishi bilan bitta [INST] blokida yuboriladi.
    override fun systemOnlyPrefix(): String? = null

    override fun buildPrompt(history: List<StoredMessage>, userPrompt: String): String {
        val sb = StringBuilder("<s>")
        if (history.isEmpty()) {
            sb.append("[INST] ").append(systemPrompt()).append("\n\n").append(userPrompt).append(" [/INST]")
        } else {
            sb.append("[INST] ").append(systemPrompt()).append("\n\n").append(historyText(history.first())).append(" [/INST]")
            for ((index, msg) in history.withIndex()) {
                if (index == 0) continue
                if (historyRole(msg) == "user") {
                    sb.append("[INST] ").append(historyText(msg)).append(" [/INST]")
                } else {
                    sb.append(historyText(msg)).append("</s>")
                }
            }
            sb.append("[INST] ").append(userPrompt).append(" [/INST]")
        }
        return sb.toString()
    }
}

/** Noma'lum/generic model — teglarsiz oddiy matn shablon (eski xatti-harakat). */
class GenericPromptStrategy(private val agentCapable: Boolean = true, private val compact: Boolean = false, private val role: ModelRole? = null, private val linux: LinuxPromptMode = LinuxPromptMode.NONE, private val customChat: String? = null, private val customAgent: String? = null) : ModelPromptStrategy {
    override fun systemPrompt(): String =
        if (agentCapable) agentSystem(compact, linux = linux, customAgent = customAgent) else (customChat ?: chatSystem(role))

    override fun systemOnlyPrefix(): String = systemPrompt()

    override fun buildPrompt(history: List<StoredMessage>, userPrompt: String): String {
        val sb = StringBuilder(systemPrompt())
        for (msg in history) {
            sb.append(if (historyRole(msg) == "user") "\nUser: " else "\nAssistant: ").append(historyText(msg))
        }
        sb.append("\nUser: ").append(userPrompt).append("\nAssistant: ")
        return sb.toString()
    }
}

// MUHIM TUZATISH: bu ko'rsatma ilgari faqat DeepSeek-R1 uchun bor edi.
// Endi katalogga Qwen3-4B qo'shilgani sabab QWEN oilasiga ham kerak —
// Qwen3 o'z ichida "thinking mode"ga ega va ba'zan JSON'dan OLDIN
// <think>...</think> yozib yuborishi mumkin (Qwen2.5'da bu yo'q edi,
// lekin hint zararsiz — u faqat modelda <think> "bo'lsa" ishlaydi).
private val THINK_BEFORE_JSON_GUARD =
    "\nIf your model supports a <think> reasoning mode, never write <think> before a JSON " +
        "tool-call — start directly with your one short sentence, then the JSON. Use <think>...</think> " +
        "only right before your final plain-text answer, and keep it brief."

fun promptStrategyFor(family: ModelFamily?, agentCapable: Boolean = true, compact: Boolean = false, role: ModelRole? = null, linux: LinuxPromptMode = LinuxPromptMode.NONE, customChat: String? = null, customAgent: String? = null): ModelPromptStrategy = when (family) {
    ModelFamily.QWEN -> ChatMlPromptStrategy(
        agentCapable,
        extraHint = if (agentCapable) THINK_BEFORE_JSON_GUARD else "",
        compact = compact,
        role = role,
        linux = linux,
        customChat = customChat,
        customAgent = customAgent
    )
    ModelFamily.DEEPSEEK_R1 -> ChatMlPromptStrategy(
        agentCapable,
        // MUHIM: <think> hech qachon JSON tool-call'dan OLDIN chiqmasin — aks
        // holda javob "{" bilan boshlanmay qoladi va JSON parse (yuqoridagi
        // qat'iy qoida) buziladi. Fikrlash faqat YAKUNIY (matn) javobdan oldin,
        // qisqa holda ishlatiladi. (AgentOrchestrator.parseStep har ehtimolga
        // qarshi <think> blokini baribir avtomatik tozalab tashlaydi.)
        extraHint = if (agentCapable) THINK_BEFORE_JSON_GUARD else "",
        compact = compact,
        role = role,
        linux = linux,
        customChat = customChat,
        customAgent = customAgent
    )
    ModelFamily.LLAMA3 -> Llama3PromptStrategy(agentCapable, compact, role, linux, customChat, customAgent)
    ModelFamily.GEMMA -> GemmaPromptStrategy(agentCapable, compact, role, linux, customChat, customAgent)
    ModelFamily.PHI3 -> Phi3PromptStrategy(agentCapable, compact, role, linux, customChat, customAgent)
    ModelFamily.MISTRAL -> MistralPromptStrategy(agentCapable, compact, role, linux, customChat, customAgent)
    else -> GenericPromptStrategy(agentCapable, compact, role, linux, customChat, customAgent)
}

/**
 * Eng SO'NGGI xabarlardan boshlab orqaga hisoblab, maxChars ichiga
 * sig'adiganlarini qoldiradi (eng eski xabarlar avtomatik tashlanadi).
 * Tizim ko'rsatmasi bu yerga umuman kirmaydi — u har doim to'liq
 * saqlanadi (ModelPromptStrategy.systemPrompt() orqali alohida qo'shiladi).
 */
// MUHIM TUZATISH (3-muammo — "eski buyruq natijasini qaytarish"):
// Terminal natijasi (executor chiqargan matn) ilgari tarixga "assistant"
// (ya'ni "modelning o'zi shunday deb aytgan") sifatida qo'shilardi. Kichik
// model buni o'zining OLDINGI gapi deb qabul qilib, keyingi so'rovda ham
// xuddi shu formatdagi ("Natija (kod N): ...") matnni tabiiy davom sifatida
// qayta ishlab chiqarardi — go'yo faqat o'z ohangini takrorlagandek.
// Terminal chiqishi aslida na foydalanuvchi, na model gapi — bu atrofdan
// (qurilmadan) kelgan kuzatuv. Shu sabab endi u "user" o'rniga emas,
// alohida, aniq belgilangan teg bilan yuboriladi — model uni HECH QACHON
// o'zining oldingi javobi deb aralashtirmasin.
private fun historyRole(msg: StoredMessage): String =
    if (msg.fromUser || msg.isTerminal) "user" else "assistant"

private fun historyText(msg: StoredMessage): String =
    if (msg.isTerminal) "[TERMINAL OUTPUT — this is a computer/device result, not something you said]\n${msg.text.toEnglishForModel()}"
    else msg.text

fun trimHistory(messages: List<StoredMessage>, maxChars: Int = 6000): List<StoredMessage> {
    var currentChars = 0
    val kept = mutableListOf<StoredMessage>()
    for (msg in messages.asReversed()) {
        val len = msg.text.length
        if (currentChars + len > maxChars) break
        kept.add(msg)
        currentChars += len
    }
    return kept.reversed()
}
