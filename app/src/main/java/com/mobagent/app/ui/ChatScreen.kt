package com.mobagent.app.ui

import com.mobagent.app.i18n.tr
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.asImageBitmap
import com.mobagent.app.history.ImageStore
import com.mobagent.app.i18n.AppLang
import com.mobagent.app.i18n.AppLanguage
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Terminal
import com.mobagent.app.linux.LinuxEnvironment
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.mobagent.app.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.agent.AgentOrchestrator
import com.mobagent.app.agent.AgentTaskService
import com.mobagent.app.agent.ToolApprovalBus
import com.mobagent.app.agent.CommandExecutor
import com.mobagent.app.agent.GenerationController
import com.mobagent.app.agent.ToolCall
import com.mobagent.app.engine.LlamaEngine
import com.mobagent.app.online.ApiKeyStore
import com.mobagent.app.online.OnlineChatConfig
import com.mobagent.app.online.OnlineSession
import com.mobagent.app.history.ChatHistoryStore
import com.mobagent.app.history.StoredMessage
import com.mobagent.app.history.Block
import com.mobagent.app.history.CmdStatus
import com.mobagent.app.agent.FileDiff
import com.mobagent.app.agent.DiffBackupStore
import com.mobagent.app.history.DiffState
import com.mobagent.app.model.ModelCatalog
import com.mobagent.app.model.ModelItem
import com.mobagent.app.model.downloadedModelFile
import com.mobagent.app.model.STANDARD_CTX
import com.mobagent.app.permission.effectiveWorkingDir
import com.mobagent.app.permission.rememberStoragePermissionState
import com.mobagent.app.prompt.LinuxPromptMode
import com.mobagent.app.prompt.ModelFamily
import com.mobagent.app.prompt.linuxPromptMode
import com.mobagent.app.prompt.ToolGrammar
import com.mobagent.app.prompt.promptStrategyFor
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val isTerminal: Boolean = false,
    // AI javobi (turn) bo'laklari: matn + buyruq kartasi + diff. Bo'sh bo'lsa — eski oddiy xabar.
    val blocks: List<Block> = emptyList(),
    // Foydalanuvchi biriktirgan rasmlar (ImageStore fayl nomlari) — faqat onlayn chat.
    val images: List<String> = emptyList()
)

// Bitta so'rovga necha ketma-ket tool-call'gacha avtomatik davom etilishi
// mumkinligining chegarasi — cheksiz sikl yoki modelning adashib ketishidan
// himoya. Har qadam baribir Security Guard Dialog orqali tasdiqlanadi.
private const val MAX_CHAIN_STEPS = 15

/** Foydalanuvchi tasdig'isiz avtomatik bajariladigan (faqat o'qiydigan, internet) asboblar. */
private val AUTO_RUN_ACTIONS = setOf("web_search", "web_fetch")

// Katta AI'lar (ChatGPT/Claude) kabi: AI javobi pufaksiz, to'liq kenglikda, avatarsiz.
// Avatar qaytarilsin desangiz — true qiling.
private const val SHOW_AI_AVATAR = false

// Tasdiqlashni kutayotgan buyruq + zanjirdagi nechinchi qadam ekani
// (dialog matnida "2/6-qadam" kabi ko'rsatish va chegarani nazorat qilish uchun).
private data class PendingTool(val call: ToolCall, val step: Int, val blockId: String)

private fun ChatMessage.toStored() = StoredMessage(fromUser, text, isTerminal, blocks, images)

// Diskdan yuklanganda tasdiq kutayotgan/bajarilayotgan buyruqni davom ettirib bo'lmaydi — o'tkazib yuborilgan deb belgilaymiz.
private fun StoredMessage.toUi() = ChatMessage(fromUser, text, isTerminal, blocks.map { b ->
    when {
        b is Block.Command && (b.status == CmdStatus.PENDING || b.status == CmdStatus.RUNNING) -> b.copy(status = CmdStatus.SKIPPED)
        // Zaxira nusxasiz (eski format) diff'ni endi bekor qilib bo'lmaydi.
        b is Block.Diff && b.id.isEmpty() -> b.copy(state = DiffState.ACCEPTED)
        else -> b
    }
}, images)

private fun turnOf(blocks: List<Block>) =
    ChatMessage(false, blocks.filterIsInstance<Block.Text>().joinToString("\n\n") { it.text }, false, blocks)

/** Modelga beriladigan tarix: bloklar eski formatga (matn + terminal natijasi) yoyiladi. */
private fun ChatMessage.toHistory(): List<StoredMessage> =
    if (blocks.isEmpty()) listOf(StoredMessage(fromUser, text, isTerminal, images = images))
    else blocks.mapNotNull { b ->
        when (b) {
            is Block.Text -> StoredMessage(false, b.text)
            is Block.Command ->
                if (b.status == CmdStatus.DONE || b.status == CmdStatus.FAILED)
                    StoredMessage(false, "Natija (kod ${b.exitCode ?: 0}):\n${b.output}", isTerminal = true)
                else null
            is Block.Diff -> null
        }
    }

private val ACTION_START = Regex("""\{\s*"action"""")
private val ACTION_PARTIAL = Regex("""\{\s*(?:"(?:a(?:c(?:t(?:i(?:on?)?)?)?)?)?)?$""")

/** Jonli oqimda xom JSON ko'rinmasin: faqat JSON'dan oldingi izoh ko'rsatiladi. */
private fun visibleStreamText(raw: String, agentCapable: Boolean): String {
    var t = raw.replace(Regex("(?s)<think>.*?(</think>|$)"), "")
    if (agentCapable) {
        val cut = ACTION_START.find(t)?.range?.first ?: ACTION_PARTIAL.find(t)?.range?.first
        if (cut != null) t = t.substring(0, cut)
        t = t.trimEnd().removeSuffix("```json").removeSuffix("```")
    }
    return t.trim()
}

/**
 * System promptning KV-kesh faylini modelId + kontekst hajmi + system
 * promptning o'zi (SHA-256 orqali) bo'yicha nomlaydi. Prompt matni
 * o'zgarsa (masalan ModelPromptStrategy.kt tahrirlansa), hash ham
 * o'zgaradi — demak eski, endi mos kelmaydigan kesh avtomatik e'tiborsiz
 * qoldiriladi (fayl nomi mos kelmaydi), eskirgan holatni yuklab olib
 * xato natija berish xavfi yo'q.
 */
private fun kvCacheFile(context: android.content.Context, modelId: String?, nCtx: Int, prefix: String): java.io.File {
    val dir = java.io.File(context.filesDir, "kvcache").apply { mkdirs() }
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(prefix.toByteArray())
    val hash = digest.joinToString("") { "%02x".format(it) }.take(16)
    return java.io.File(dir, "${modelId ?: "generic"}_ctx${nCtx}_$hash.bin")
}

@Composable
fun ChatScreen(
    modelName: String,
    modelPath: String? = null,
    modelId: String? = null,
    sessionId: String? = null,
    nCtx: Int = STANDARD_CTX,
    onBack: () -> Unit,
    // Chap yuqoridagi menyu tugmasi yon panelni ochadi (berilmasa — orqaga).
    onMenuClick: (() -> Unit)? = null,
    onNewChat: () -> Unit = {},
    // Sarlavhadagi model ro'yxatidan boshqa model tanlanganda (yangi suhbat
    // shu model bilan ochiladi) va "Modellarni boshqarish" bosilganda.
    onSwitchModel: (ModelItem, java.io.File) -> Unit = { _, _ -> },
    onManageModels: () -> Unit = {},
    // Onlayn chatda: sarlavhadagi ro'yxatdan boshqa model tanlanganda (xizmat id, model) va
    // "API kalitlarni boshqarish" bosilganda.
    onSwitchOnlineModel: (String, String) -> Unit = { _, _ -> },
    onManageApiKeys: () -> Unit = {},
    // Pullik rejim (MobAgent Cloud): yozish maydonidagi model tugmasidan model tanlanganda (id, nom)
    // va tarif kerak bo'lganda tariflar ekranini ochish.
    onSwitchCloudModel: (String, String) -> Unit = { _, _ -> },
    onOpenPlans: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val storagePermission = rememberStoragePermissionState()
    val historyStore = remember { ChatHistoryStore(context) }
    val diffStore = remember { DiffBackupStore(context) }

    // Haqiqiy loyihada bular ViewModel ichida, model tanlanganda yuklanadi
    val engine = remember { LlamaEngine() }
    // Ruxsat berilsa (yoki keyinroq berilsa) executor telefon xotirasining
    // ildiz papkasida ishlaydi; berilmaguncha faqat ilovaning shaxsiy
    // (sandbox) papkasida — CommandExecutor.kt hech qachon shaxsiy
    // papkadan tashqariga sirg'alib chiqmaydi.
    val executor = remember(storagePermission.isGranted) {
        // context — Termux uslubidagi toybox applet'larini ilovaning shaxsiy
        // papkasiga bir martalik o'rnatish (symlink) uchun kerak.
        CommandExecutor(effectiveWorkingDir(context), context)
    }
    // Har model oilasi (Qwen/Llama-3/Gemma/DeepSeek-R1) o'z chat-shablonini
    // va tizim ko'rsatmasini oladi — ModelPromptStrategy.kt'ga qarang.
    // Onlayn suhbat (o'z API kaliti bilan): modelId "online:<xizmat>:<model>" ko'rinishida keladi.
    // Kalit ApiKeyStore'dan o'qiladi; yo'q bo'lsa bo'sh kalit -> xizmat 401 qaytaradi va foydalanuvchi
    // aniq xabar ko'radi (lokal dvigatelga adashib tushmaslik uchun).
    val onlineCfg = remember(modelId, modelPath) { if (modelPath == null) OnlineChatConfig.parse(modelId) else null }
    val onlineSession = remember(onlineCfg) {
        onlineCfg?.let { cfg ->
            OnlineSession(cfg.provider!!, ApiKeyStore.get(context, cfg.providerId).orEmpty(), cfg.model)
        }
    }
    val family = remember(modelId) { ModelCatalog.byId(modelId)?.family }
    // 1B-2B modellar buyruq/tool-call formatini ishonchli bajara olmaydi —
    // shuning uchun ular FAQAT suhbat (Chat) rejimida, 3B+ esa to'liq
    // Agent (buyruq bajarish) rejimida ishlaydi (ModelItem.isAgentCapable).
    // Onlayn modellar har doim agent (asboblar bilan); oflayn — modelning o'z imkoniyatiga qarab.
    val agentCapable = remember(modelId) { onlineCfg != null || (ModelCatalog.byId(modelId)?.isAgentCapable ?: true) }
    val compactAgent = remember(modelId) { ModelCatalog.byId(modelId)?.isCompactAgent ?: false }
    val modelRole = remember(modelId) { ModelCatalog.byId(modelId)?.role }
    val customChat = remember(modelId) { ModelCatalog.byId(modelId)?.systemPrompt }
    // Admindan kelgan qo'shimcha agent ko'rsatmasi (faqat oflayn Agent/Kuchli modellar; onlayn modellarda katalog yo'q)
    val customAgent = remember(modelId) { ModelCatalog.byId(modelId)?.agentPrompt }
    // Linux muhiti (o'rnatilgan+yoqilgan) bo'lsa model shu haqda biladi. Holat har recomposition'da
    // qayta o'qiladi — fon o'rnatish tugagach keyingi xabarda prompt o'zi yangilanadi.
    // Ixcham (1.5B-2B) agentda execute_bash yo'q, shuning uchun unga Linux haqida aytilmaydi.
    val linuxMode = linuxPromptMode(context).let { if (compactAgent || !agentCapable) LinuxPromptMode.NONE else it }
    val promptStrategy = remember(modelId, linuxMode) { promptStrategyFor(family, agentCapable, compactAgent, modelRole, linuxMode, customChat, customAgent) }
    // Agent rejimida generatsiya GBNF grammatika bilan cheklanadi (yaroqli JSON
    // kafolati). DeepSeek-R1 bundan mustasno: u JSON'dan oldin erkin <think>
    // yozadi, grammatika buni keraksiz buzardi.
    val toolGrammar = remember(family, agentCapable, compactAgent, onlineCfg) {
        when {
            // GBNF grammatika faqat lokal dvigatel uchun; onlayn xizmatlarda u yo'q.
            onlineCfg != null -> null
            !agentCapable || family == ModelFamily.DEEPSEEK_R1 -> null
            compactAgent -> ToolGrammar.COMPACT
            else -> ToolGrammar.GRAMMAR
        }
    }
    val orchestrator = remember(executor, promptStrategy, nCtx, toolGrammar, onlineSession, linuxMode) {
        AgentOrchestrator(engine, executor, promptStrategy, nCtx, agentCapable, toolGrammar, onlineSession, linuxMode)
    }

    // Agentning "kayfiyati" (AgentFace.kt) — foydalanuvchi so'ragan
    // holat-animatsiyasi: o_o (o'ylamoqda), >_< (buyruq/tasdiq kutmoqda,
    // zo'riqqan), ^_^ (muvaffaqiyatli tugatdi, bir zumga ko'rinadi).
    var agentMood by remember { mutableStateOf(AgentMood.IDLE) }

    // Joriy suhbat sessiyasi ID'si: eski suhbat ochilsa shu ID bilan davom
    // etadi, yangi suhbat bo'lsa birinchi xabar yuborilganda yaratiladi.
    var currentSessionId by remember { mutableStateOf(sessionId) }
    var currentModelId by remember { mutableStateOf(modelId) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    // RAM yetmasligi mumkin bo'lgan modelga almashtirishdan oldin ogohlantirish.
    var pendingSwitch by remember { mutableStateOf<Pair<ModelItem, java.io.File>?>(null) }
    // Tarif sahifasidan qaytganda yozilgan xabar tiklanadi (faqat pullik rejimda).
    var input by remember {
        mutableStateOf(if (onlineCfg?.providerId == com.mobagent.app.billing.CloudApi.PROVIDER_ID) com.mobagent.app.billing.PendingPrompt.take() else "")
    }
    // Oxirgi foydalanuvchi xabari tahrirlanayotgan bo'lsa — uning indeksi.
    var editingIdx by remember { mutableStateOf<Int?>(null) }

    // Bitta AI javobi = bitta ChatMessage (blocks). Oxirgi xabar AI'niki bo'lsa,
    // yangi bo'laklar UNGA qo'shiladi, aks holda yangi javob boshlanadi.
    fun appendToTurn(vararg add: Block) {
        val last = messages.lastOrNull()
        messages = if (last != null && !last.fromUser && !last.isTerminal && last.blocks.isNotEmpty())
            messages.dropLast(1) + turnOf(last.blocks + add)
        else messages + turnOf(add.toList())
    }
    fun updateCommand(id: String, transform: (Block.Command) -> Block.Command) {
        messages = messages.map { m ->
            if (m.blocks.any { it is Block.Command && it.id == id })
                turnOf(m.blocks.map { b -> if (b is Block.Command && b.id == id) transform(b) else b })
            else m
        }
    }

    // Ovozdan matnga: tizimning o'z tanish oynasi (Google) ochiladi va natija
    // yozish joyiga qo'shiladi. Bizning ilovaga RECORD_AUDIO ruxsati kerak emas.
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                input = if (input.isBlank()) text else input.trimEnd() + " " + text
            }
        }
    }
    // Onlayn chatda biriktirilgan (hali yuborilmagan) rasmlar — ImageStore fayl nomlari.
    val isOnlineChat = onlineSession != null
    // MobAgent Cloud (pullik): tarif holati serverdan olinadi; xarid tasdiqlanganda va har javobdan keyin yangilanadi.
    val isCloud = onlineCfg?.providerId == com.mobagent.app.billing.CloudApi.PROVIDER_ID
    var cloudPlan by remember { mutableStateOf<com.mobagent.app.billing.CloudPlan?>(null) }
    var limitWarnDismissedFor by remember { mutableStateOf(0L) }
    val cloudVerifiedTick by com.mobagent.app.billing.BillingManager.verified.collectAsState()
    var cloudMenuOpen by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    var pendingImages by remember { mutableStateOf(listOf<String>()) }
    var pendingTool by remember { mutableStateOf<PendingTool?>(null) }
    var lastUserPrompt by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }
    LaunchedEffect(isCloud, cloudVerifiedTick, isThinking) {
        if (isCloud && !isThinking) cloudPlan = com.mobagent.app.billing.CloudApi.plan()
    }
    // Tanlangan model serverda yo'q bo'lsa (kaliti yo'q yoki olib tashlangan) — ro'yxatdagi birinchi modelga o'tamiz.
    LaunchedEffect(cloudPlan, onlineCfg?.model) {
        val ms = cloudPlan?.models.orEmpty()
        if (isCloud && ms.isNotEmpty() && ms.none { it.id == onlineCfg?.model }) {
            onSwitchCloudModel(ms.first().id, ms.first().name)
        }
    }
    // Generatsiya davomida jonli (token-token) ko'rinadigan matn — ilgari
    // javob faqat TO'LIQ tugagach bitta butun holda ko'rinardi, shuning
    // uchun sekin qurilmada foydalanuvchi hech narsa ko'rmay "osilib qoldi"
    // deb o'ylardi.
    var streamingText by remember { mutableStateOf("") }
    var isModelReady by remember { mutableStateOf(modelPath == null) }
    var modelLoadError by remember { mutableStateOf<String?>(null) }
    var modelLoadRetry by remember { mutableStateOf(0) }
    // Bitta suhbat/model uchun barqaror kalit — GenerationController orqali
    // qaysi voqea qaysi ekranga tegishli ekanini aniqlash uchun.
    val sessionKey = remember(modelPath, modelName) { modelPath ?: modelName }
    // Compose'da "isThinking = true" o'zgarishi darhol ekranga chizilmasligi
    // mumkin (bir necha millisoniya kechikishi bor) — shu oraliqda tugma
    // ikki marta tez-tez bosilsa, ikkalasi ham "band emas" deb o'tib ketishi
    // va bitta native AI context'iga bir vaqtda ikkita so'rov kirib borishi
    // mumkin edi. AtomicBoolean UI recompozitsiyasini kutmay, DARHOL bloklaydi.
    val sendGuard = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    // Vazifa tugagach ("javob tayyor" yoki "buyruqni tasdiqlang") ilova
    // fonda bo'lsa ham bildirishnoma ko'rsatish uchun. Rad etilsa, faqat
    // bildirishnoma ko'rinmaydi — chat ishlashda davom etadi.
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Eski suhbat ochilgan bo'lsa, uning xabarlarini diskdan yuklaymiz —
    // ilgari "Suhbatlar tarixi" degan tushuncha umuman yo'q edi.
    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            historyStore.load(sessionId)?.let { saved ->
                messages = saved.messages.map { it.toUi() }
                currentModelId = saved.modelId
            }
        }
    }

    suspend fun persist(msgs: List<ChatMessage>) {
        if (msgs.isEmpty()) return
        val id = currentSessionId ?: historyStore.createSession(modelName, modelPath, currentModelId).id.also {
            currentSessionId = it
        }
        val existing = historyStore.load(id)
        val now = System.currentTimeMillis()
        historyStore.save(
            com.mobagent.app.history.ChatSession(
                id = id,
                modelName = modelName,
                modelPath = modelPath,
                modelId = currentModelId,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                messages = msgs.map { it.toStored() },
                title = existing?.title
            )
        )
    }

    // Oflayn rejimda: yuklab olingan .gguf fayl manzili shu yerda native
    // kontekstga ulanadi. Ilgari bu qadam umuman yo'q edi — model hech qachon
    // ishga tushmasdan, chat bo'sh javob qaytarardi.
    LaunchedEffect(modelPath, modelLoadRetry) {
        if (modelPath != null) {
            isModelReady = false
            modelLoadError = null
            var warmupPending: Pair<String, String>? = null
            try {
                withContext(Dispatchers.IO) {
                    engine.loadModel(modelPath, nCtx = nCtx)

                    // MUHIM (tezlik #3): "KV-kesh qayta ishlatish" (lastSequence)
                    // faqat BITTA suhbat sessiyasi ICHIDA ishlaydi — model hozir
                    // ENDI yuklandi, demak lastSequence bo'sh va system prompt
                    // birinchi xabarda noldan decode qilinadi. Buning oldini olish
                    // uchun: avval diskdagi keshdan tiklashga urinamiz; topilmasa
                    // (shu model+nCtx+prompt matni uchun ilk marta), bir martalik
                    // "warmup" qilib, keyingi safar uchun saqlab qo'yamiz. Gemma
                    // uchun systemOnlyPrefix() null (mustaqil prefiksi yo'q) —
                    // bunday holda bu optimallashtirish jimgina o'tkazib yuboriladi.
                    // TUZATISH: faqat TEZ bo'lgan tiklash shu yerda qoladi.
                    // Sekin warmup (system promptni decode qilish) endi
                    // model "tayyor" bo'lgandan KEYIN, fonda ishlaydi.
                    promptStrategy.systemOnlyPrefix()?.let { prefix ->
                        val cacheFile = kvCacheFile(context, modelId, nCtx, prefix)
                        warmupPending = if (engine.tryRestoreCachedState(cacheFile.absolutePath)) null
                                        else prefix to cacheFile.absolutePath
                    }
                }
                isModelReady = true
                warmupPending?.let { (prefix, path) ->
                    warmupPending = null
                    launch(Dispatchers.IO) { engine.warmupSystemPromptAndCacheAsync(prefix, path) }
                }
            } catch (e: Exception) {
                // Ilgari xato shu yerda yutilib, isModelReady abadiy false
                // bo'lib qolardi — foydalanuvchi hech narsa ko'rmasdi.
                modelLoadError = e.message ?: tr("Model yuklanmadi (noma'lum xato)")
            }
        }
    }

    // MUHIM: bu yerda haqiqiy generatsiya YO'Q — u GenerationController
    // (process-darajasidagi singleton) ichida ketadi, shuning uchun Activity
    // qayta yaratilsa ham (ekranni aylantirish, xotira tozalash va h.k.)
    // davom etadi. Bu LaunchedEffect faqat NATIJANI kuzatib, ekranga
    // chiqaradi — shu sabab `Unit` bilan kalitlangan (har mount'da qayta
    // ulanadi) va ichkarida sessionKey bo'yicha filtrlaydi.
    LaunchedEffect(Unit) {
        GenerationController.state.collect { gen ->
            if (gen.sessionKey != sessionKey) return@collect
            when (gen.phase) {
                GenerationController.Phase.IDLE -> {
                    // Boshqa ekrandan meros holat emas — hech narsa qilmaymiz.
                }
                GenerationController.Phase.THINKING -> {
                    isThinking = true
                    streamingText = gen.partialText
                    agentMood = AgentMood.THINKING
                }
                GenerationController.Phase.DONE -> {
                    val bodyText = gen.finalText.trim().ifEmpty {
                        tr("Model bo'sh javob qaytardi. Boshqa savol bilan qayta urinib ko'ring.")
                    }
                    val text = if (gen.cancelled) "$bodyText\n\n${tr("[to'xtatildi]")}" else bodyText
                    appendToTurn(Block.Text(text))
                    isThinking = false
                    streamingText = ""
                    sendGuard.set(false)
                    agentMood = if (gen.cancelled) AgentMood.IDLE else AgentMood.SUCCESS
                    persist(messages)
                    AgentTaskService.stop(context)
                    AgentTaskService.notifyFinished(context, modelName, text)
                    GenerationController.consume()
                }
                GenerationController.Phase.TOOL_PENDING -> {
                    isThinking = false
                    streamingText = ""
                    sendGuard.set(false)
                    agentMood = AgentMood.IDLE
                    val blockId = java.util.UUID.randomUUID().toString()
                    val call = gen.toolCall!!
                    // Izoh + buyruq kartasi BITTA javobda; tasdiq — kartadagi "Bajarish" tugmasi.
                    appendToTurn(
                        *listOfNotNull(
                            gen.preamble.takeIf { it.isNotBlank() }?.let { Block.Text(it) },
                            Block.Command(blockId, call.describe(), CmdStatus.PENDING, step = gen.chainStep)
                        ).toTypedArray()
                    )
                    pendingTool = PendingTool(call, gen.chainStep, blockId)
                    ToolApprovalBus.pendingBlockId = blockId
                    persist(messages)
                    // web_search / web_fetch ruxsat so'ramaydi (pastdagi LaunchedEffect avtomatik bajaradi),
                    // shuning uchun ular uchun tasdiqlash bildirishnomasi ham chiqarilmaydi.
                    if (call.action !in AUTO_RUN_ACTIONS) {
                        // Endi ilovani ochib tasdiqlash kerakligi bildirishnomada ham
                        // ko'rinadi — ilgari foydalanuvchi buni faqat ilova ichida ko'rardi.
                        AgentTaskService.start(context, tr("Buyruqni tasdiqlash kerak: %1\$s", gen.toolCall.describe()))
                        // Alohida bildirishnoma: "Ruxsat berish" / "Rad etish" tugmalari bilan.
                        AgentTaskService.notifyApproval(context, blockId, call.describe(), gen.chainStep, MAX_CHAIN_STEPS)
                    }
                    GenerationController.consume()
                }
                GenerationController.Phase.ERROR -> {
                    isThinking = false
                    streamingText = ""
                    sendGuard.set(false)
                    agentMood = AgentMood.IDLE
                    appendToTurn(
                        Block.Text(
                            if (onlineCfg != null) tr("Xato: %1\$s", gen.errorMessage)
                            else tr("Xato: javob generatsiya qilinmadi (%1\$s).\nModelni qayta yuklab ko'ring.", gen.errorMessage)
                        )
                    )
                    persist(messages)
                    AgentTaskService.stop(context)
                    GenerationController.consume()
                }
            }
        }
    }

    // "^_^" holati bir necha soniyadan keyin avtomatik yo'qoladi — doimiy
    // ko'rinib turmasligi uchun.
    LaunchedEffect(agentMood) {
        if (agentMood == AgentMood.SUCCESS) {
            kotlinx.coroutines.delay(1800)
            agentMood = AgentMood.IDLE
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // MUHIM TUZATISH: ilgari bu yerda SHARTSIZ engine.close() chaqirilardi.
            // Lekin GenerationController process-darajasida yashaydi va aynan
            // ekran aylantirilganda yoki Activity qayta yaratilganda ham davom
            // etishi uchun mo'ljallangan (GenerationController.kt'dagi izohga
            // qarang). Agar composition tark etilayotgan paytda shu sessionKey
            // uchun hali generatsiya (THINKING) davom etayotgan bo'lsa, native
            // context'ni shu yerda yopish — fon tredda hali llama_decode()
            // chaqirayotgan xotirani ozod qilib yuborardi (use-after-free),
            // natijada native jarayon osilib qolishi yoki butunlay qulashi
            // mumkin edi — foydalanuvchiga bu xuddi "javob hech qachon kelmadi"
            // bo'lib ko'rinardi. Shu holatda yopishni o'tkazib yuboramiz: yangi
            // composition o'z alohida yangi model nusxasini yuklaydi (vaqtincha
            // ikki barobar RAM sarflanadi), eski nusxa esa generatsiya
            // tugagach process bilan birga tabiiy ravishda tozalanadi.
            val gen = GenerationController.state.value
            val stillGenerating = gen.sessionKey == sessionKey && gen.phase == GenerationController.Phase.THINKING
            if (modelPath != null && !stillGenerating) {
                engine.close()
            }
            ToolApprovalBus.pendingBlockId = null
            AgentTaskService.stop(context)
        }
    }

    // Permission & Execution Guard: endi dialog emas, javob ichidagi kartadagi
    // "Bajarish" / "O'tkazish" tugmalari (Copilot uslubi). Xavfsizlik o'zgarmagan —
    // buyruq foydalanuvchi bosmaguncha HECH QACHON bajarilmaydi.
    fun pendingDiffs(): List<Block.Diff> =
        messages.flatMap { it.blocks }.filterIsInstance<Block.Diff>()
            .filter { it.state == DiffState.PENDING && it.id.isNotEmpty() }

    fun setDiffState(id: String, st: DiffState) {
        messages = messages.map { m ->
            if (m.blocks.any { it is Block.Diff && it.id == id })
                turnOf(m.blocks.map { b -> if (b is Block.Diff && b.id == id) b.copy(state = st) else b })
            else m
        }
    }

    fun acceptDiff(id: String) {
        setDiffState(id, DiffState.ACCEPTED)
        scope.launch(Dispatchers.IO) { diffStore.drop(id) }
        scope.launch { persist(messages) }
    }

    // "Bekor qilish": faylni tahrirdan OLDINGI holatiga qaytaradi (yangi fayl bo'lsa o'chiradi).
    fun discardDiff(id: String) {
        val d = pendingDiffs().firstOrNull { it.id == id } ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { diffStore.revert(d) }
            if (ok) {
                setDiffState(id, DiffState.REVERTED)
                persist(messages)
            } else {
                Toast.makeText(context, tr("Faylni qaytarib bo'lmadi (zaxira nusxa topilmadi)"), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Oxirgi AI javobini o'chirib, o'sha savolga qaytadan javob oladi.
    // Faqat matnli javoblar uchun chaqiriladi (buyruq/diff bo'lsa tugma ko'rinmaydi).
    fun regenerateLast() {
        if (pendingTool != null || isThinking) return
        if (!sendGuard.compareAndSet(false, true)) return
        val userIdx = messages.indexOfLast { it.fromUser }
        if (userIdx < 0) { sendGuard.set(false); return }
        val prompt = messages[userIdx].text
        val promptImages = messages[userIdx].images
        val historyBefore = messages.take(userIdx).flatMap { it.toHistory() }
        messages = messages.take(userIdx + 1)
        lastUserPrompt = prompt
        isThinking = true
        streamingText = ""
        agentMood = AgentMood.THINKING
        AgentTaskService.start(context, tr("Javob tayyorlanmoqda..."))
        scope.launch { persist(messages) }
        GenerationController.startThink(sessionKey, orchestrator, prompt, historyBefore, promptImages)
    }

    // Oxirgi foydalanuvchi xabarini tahrirlab qayta yuboradi: eski AI javobi
    // o'chiriladi va yangi matn bo'yicha javob qaytadan yaratiladi.
    fun editLastUserMessage(newText: String) {
        val text = newText.trim()
        if (text.isEmpty() || pendingTool != null || isThinking) return
        if (!sendGuard.compareAndSet(false, true)) return
        val userIdx = messages.indexOfLast { it.fromUser }
        if (userIdx < 0) { sendGuard.set(false); return }
        val historyBefore = messages.take(userIdx).flatMap { it.toHistory() }
        val editImages = messages[userIdx].images
        messages = messages.take(userIdx) + messages[userIdx].copy(text = text)
        editingIdx = null
        lastUserPrompt = text
        isThinking = true
        streamingText = ""
        agentMood = AgentMood.THINKING
        AgentTaskService.start(context, tr("Javob tayyorlanmoqda..."))
        scope.launch { persist(messages) }
        GenerationController.startThink(sessionKey, orchestrator, text, historyBefore, editImages)
    }

    fun skipPending(blockId: String) {
        if (pendingTool?.blockId != blockId) return
        pendingTool = null
        ToolApprovalBus.pendingBlockId = null
        updateCommand(blockId) { it.copy(status = CmdStatus.SKIPPED) }
        AgentTaskService.stop(context)
        scope.launch { persist(messages) }
    }

    fun runPending(blockId: String) {
        val pending = pendingTool ?: return
        if (pending.blockId != blockId) return
        val call = pending.call
        val stepNow = pending.step
        pendingTool = null
        ToolApprovalBus.pendingBlockId = null
        AgentTaskService.cancelApproval(context)
        scope.launch {
            updateCommand(blockId) { it.copy(status = CmdStatus.RUNNING) }
            agentMood = AgentMood.WORKING
            // Fayl yozadigan buyruq bo'lsa, oldingi holatni eslab qolamiz — keyin diff yasaymiz.
            val workDir = effectiveWorkingDir(context)
            val snapshot = withContext(Dispatchers.IO) { FileDiff.snapshotFor(call, workDir, context) }
            val result = orchestrator.executeApprovedTool(call)
            val diffs = withContext(Dispatchers.IO) { FileDiff.diffAgainst(snapshot, diffStore) }

            val (header, body) = splitTerminalMessage(result)
            val exit = Regex("""kod (-?\d+)""").find(header)?.groupValues?.get(1)?.toIntOrNull()
            val failed = exit != null && exit != 0
            updateCommand(blockId) {
                it.copy(status = if (failed) CmdStatus.FAILED else CmdStatus.DONE, output = body.trim(), exitCode = exit)
            }
            if (diffs.isNotEmpty()) {
                // Bir faylga keyingi tahrir kelsa, oldingisi avtomatik qabul qilingan hisoblanadi
                // (yangi tahrir unga tayanadi) — shunda har faylda bittadan ko'rib chiqiladigan diff qoladi.
                val newPaths = diffs.map { it.path }.toSet()
                pendingDiffs().filter { it.path in newPaths }.forEach { acceptDiff(it.id) }
                appendToTurn(*diffs.toTypedArray())
            }
            persist(messages)

            if (stepNow >= MAX_CHAIN_STEPS) {
                val limitMsg = tr("Zanjir chegarasiga (%1\$s qadam) yetdi. Davom ettirish uchun yangi xabar yozing.", MAX_CHAIN_STEPS)
                appendToTurn(Block.Text(limitMsg))
                persist(messages)
                AgentTaskService.stop(context)
                AgentTaskService.notifyFinished(context, modelName, limitMsg)
                return@launch
            }

            isThinking = true
            streamingText = ""
            agentMood = AgentMood.THINKING
            AgentTaskService.start(context, tr("Zanjir davom etmoqda (%1\$s/%2\$s)...", stepNow + 1, MAX_CHAIN_STEPS))
            GenerationController.continueAfterTool(
                sessionKey, orchestrator, lastUserPrompt, result,
                messages.flatMap { it.toHistory() }, nextChainStep = stepNow + 1
            )
        }
    }

    // Faqat o'qiydigan internet asboblari (web_search, web_fetch) foydalanuvchidan ruxsat so'ramasdan darrov bajariladi.
    // Fayl/terminalga tegadigan boshqa barcha buyruqlar avvalgidek tasdiq kutadi.
    LaunchedEffect(pendingTool?.blockId) {
        val p = pendingTool ?: return@LaunchedEffect
        if (p.call.action in AUTO_RUN_ACTIONS) runPending(p.blockId)
    }

    // Bildirishnomadagi "Ruxsat berish" / "Rad etish" tugmalaridan kelgan qaror.
    val approvalDecision by ToolApprovalBus.decisions.collectAsState()
    LaunchedEffect(approvalDecision) {
        val d = approvalDecision ?: return@LaunchedEffect
        ToolApprovalBus.decisions.value = null
        if (pendingTool?.blockId == d.blockId) {
            if (d.approve) runPending(d.blockId) else skipPending(d.blockId)
        }
    }

    // Terminal endi yon paneldan ochiladi (MainActivity.kt): qatlam ChatScreen ustida turadi,
    // shuning uchun model, kiritilgan matn va generatsiya yo'qolmaydi.
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(BgApp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chap tepada 3 qavatli menyu belgisi (avval orqaga strelkasi edi).
            Icon(
                Icons.Filled.Menu,
                contentDescription = tr("Menyu"),
                tint = TextSecondary,
                modifier = Modifier.size(22.dp).clickable(onClick = onMenuClick ?: onBack)
            )
            Spacer(Modifier.width(12.dp))
            // Model nomi bosilsa vertikal model ro'yxati ochiladi (faqat oflayn
            // rejimda va generatsiya paytida emas — o'rtada model almashmasligi uchun).
            val canSwitch = (modelPath != null || (onlineCfg != null && !isCloud)) && !isThinking && pendingTool == null
            Box(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = canSwitch) { modelMenuOpen = !modelMenuOpen }
                        .padding(vertical = 2.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onlineCfg != null) {
                        // Onlayn suhbat: xizmatning (OpenAI, Gemini...) ikonkasi.
                        ProviderAvatar(onlineCfg.providerId, size = 34.dp, iconSize = 22.dp)
                    } else if (modelPath == null) {
                        Box(
                            modifier = Modifier.size(34.dp).clip(CircleShape).background(BgCardElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            LogoMark(fontSize = 12.sp)
                        }
                    } else {
                        AgentAvatar(family, size = 34.dp, iconSize = 18.dp, neutral = true)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                modelName,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (modelPath != null || (onlineCfg != null && !isCloud)) {
                                Icon(
                                    if (modelMenuOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = tr("Modelni almashtirish"),
                                    tint = TextMuted,
                                    modifier = Modifier.padding(start = 2.dp).size(18.dp)
                                )
                            }
                        }
                        Text(
                            when {
                                onlineCfg != null -> tr("%1\$s · onlayn agent", onlineCfg.model)
                                modelPath == null -> tr("Oflayn agent ishlamoqda")
                                !agentCapable -> tr("Chat rejimi (buyruq bajarilmaydi) · %1\$sK kontekst", nCtx / 1024)
                                else -> tr("Agent rejimi · %1\$sK kontekst", nCtx / 1024)
                            },
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                if (modelMenuOpen && canSwitch && onlineCfg != null) {
                    // Onlayn: ro'yxat API kalitning o'zidan olinadi (xizmat qaysi modellarni bersa, shular).
                    ModelSwitcherPopupOnlineHost(
                        cfg = onlineCfg,
                        onDismiss = { modelMenuOpen = false },
                        onManage = { modelMenuOpen = false; onManageApiKeys() },
                        onSelect = { m ->
                            modelMenuOpen = false
                            ApiKeyStore.setModel(context, onlineCfg.providerId, m)
                            onSwitchOnlineModel(onlineCfg.providerId, m)
                        }
                    )
                } else if (modelMenuOpen && canSwitch) {
                    // Diskdagi yuklab olingan modellar ro'yxati (menyu ochilganda o'qiladi).
                    val downloaded = remember(modelMenuOpen) {
                        ModelCatalog.models.mapNotNull { m -> downloadedModelFile(context, m.id)?.let { m to it } }
                    }
                    ModelSwitcherPopup(
                        models = downloaded,
                        currentId = currentModelId,
                        onDismiss = { modelMenuOpen = false },
                        onManage = { modelMenuOpen = false; onManageModels() },
                        onSelect = { model, file ->
                            modelMenuOpen = false
                            // Chatdan almashtirishda hozirgi model RAM'ni band qilib turadi (u bo'shatiladi),
                            // shuning uchun bo'sh-RAM emas, faqat umumiy RAM nisbati tekshiriladi.
                            if (fitsTotalRam(totalRamGb(context), model)) onSwitchModel(model, file)
                            else pendingSwitch = model to file
                        }
                    )
                }
                pendingSwitch?.let { (model, file) ->
                    AlertDialog(
                        onDismissRequest = { pendingSwitch = null },
                        containerColor = BgCardElevated,
                        title = { Text(tr("Xotira yetarli bo'lmasligi mumkin"), color = TextPrimary, fontSize = 17.sp) },
                        text = {
                            Text(
                                tr("Bu model hozirgi bo'sh xotiraga sig'masligi mumkin — ilova yopilib qolishi ehtimoli bor. Boshqa ilovalarni yoping yoki Sozlamalarda kontekstni kamaytiring."),
                                color = TextSecondary, fontSize = 14.sp
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingSwitch = null
                                onSwitchModel(model, file)
                            }) { Text(tr("Baribir ochish"), color = DangerText) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingSwitch = null }) { Text(tr("Bekor qilish"), color = TextSecondary) }
                        }
                    )
                }
            }
            // Joriy holat — agentning "kayfiyati" (AgentFace.kt): kutish/
            // ishlash/muvaffaqiyat. IDLE bo'lsa hech narsa ko'rsatilmaydi.
            if (agentMood != AgentMood.IDLE) {
                AgentFace(agentMood, fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp))
            }
            // Yangi chat tugmasi (avval suhbatlar tarixi edi). Bosilsa joriy
            // suhbat yopilib, bo'sh chat (logotip + animatsiyali xabar) ochiladi.
            // Javob yozilayotgan yoki buyruq tasdig'i kutilayotgan paytda
            // o'chiq — o'rtada suhbat uzilib qolmasligi uchun.
            val canStartNew = (messages.isNotEmpty() || currentSessionId != null) && !isThinking && pendingTool == null
            Icon(
                Icons.Outlined.EditNote,
                contentDescription = tr("Yangi chat"),
                tint = if (canStartNew) TextSecondary else TextMuted,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(enabled = canStartNew) {
                        messages = emptyList()
                        currentSessionId = null
                        input = ""
                        streamingText = ""
                        lastUserPrompt = ""
                        agentMood = AgentMood.IDLE
                        onNewChat()
                    }
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val listState = rememberLazyListState()
            // Pastga tushish tugmasi: ro'yxat oxirigacha aylantirilmagan bo'lsa ko'rinadi.
            val showScrollDown by remember { derivedStateOf { listState.canScrollForward } }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                itemsIndexed(messages) { idx, msg ->
                    if (!msg.fromUser && msg.blocks.isNotEmpty()) {
                        val isLast = idx == messages.lastIndex
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            if (SHOW_AI_AVATAR) {
                                AgentAvatar(family, size = 24.dp, iconSize = 13.dp, neutral = true)
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                AgentTurnBlocks(
                                    blocks = msg.blocks,
                                    onRun = { runPending(it) },
                                    onSkip = { skipPending(it) },
                                    onAcceptDiff = { acceptDiff(it) },
                                    onDiscardDiff = { discardDiff(it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    liveText = if (isLast) visibleStreamText(streamingText, agentCapable) else null,
                                    thinking = isLast && isThinking && isModelReady && modelLoadError == null
                                )
                                // Javob tugagach ko'rinadi (yozilayotgan paytda yo'q).
                                val turnText = msg.text
                                if (turnText.isNotBlank() && !(isLast && isThinking)) {
                                    // Qayta yaratish: faqat oxirgi, faqat matnli javobda
                                    // (buyruq bajarilgan/fayl o'zgargan bo'lsa, qaytarib bo'lmaydi).
                                    val canRegenerate = isLast && pendingTool == null &&
                                        msg.blocks.all { it is Block.Text }
                                    MessageActions(
                                        text = turnText,
                                        modifier = Modifier.padding(top = 4.dp),
                                        onRegenerate = if (canRegenerate) ({ regenerateLast() }) else null
                                    )
                                    AiDisclaimer(Modifier.padding(top = 2.dp, bottom = 2.dp))
                                }
                            }
                        }
                    } else {
                        val canEdit = msg.fromUser && idx == messages.indexOfLast { it.fromUser } &&
                            !isThinking && pendingTool == null && isModelReady
                        MessageBubble(
                            msg, family,
                            canEdit = canEdit,
                            isEditing = editingIdx == idx && canEdit,
                            onStartEdit = { editingIdx = idx },
                            onCancelEdit = { editingIdx = null },
                            onConfirmEdit = { editLastUserMessage(it) }
                        )
                    }
                }
                if (modelLoadError != null) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(BgCardAlt)
                                .border(0.5.dp, BorderColor, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(tr("Model yuklanmadi"), color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(modelLoadError ?: "", color = TextMuted, fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                tr("Qayta urinish"),
                                color = AccentPurple,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { modelLoadRetry++ }
                            )
                        }
                    }
                } else if (!isModelReady) {
                    item { Text(tr("Model xotiraga yuklanmoqda..."), color = TextMuted, fontSize = 12.sp) }
                } else if (isThinking && messages.lastOrNull()?.fromUser != false) {
                    // Jonli oqim: model hozircha yaratgan matnni ko'rsatamiz.
                    // Hali bitta token ham kelmagan bo'lsa, animatsiyali yuz
                    // ("o_o") turadi — bu telefon sekin bo'lsa ham
                    // foydalanuvchiga "ishlamoqda" ekanini isbotlaydi (ilgari
                    // hech narsa ko'rinmasdi, oddiy "AI yozmoqda..." matni edi).
                    item {
                        Row(verticalAlignment = Alignment.Top) {
                            if (SHOW_AI_AVATAR) {
                                AgentAvatar(family, size = 24.dp, iconSize = 13.dp, neutral = true)
                                Spacer(Modifier.width(8.dp))
                            }
                            if (visibleStreamText(streamingText, agentCapable).isEmpty()) {
                                ThinkingFace()
                            } else {
                                MarkdownText(visibleStreamText(streamingText, agentCapable).ifEmpty { "…" }, modifier = Modifier.weight(1f), baseFontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollDown && messages.isNotEmpty(),
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(BgCardElevated)
                        .border(1.dp, BorderColor, CircleShape)
                        .clickable {
                            scope.launch {
                                val last = listState.layoutInfo.totalItemsCount - 1
                                if (last >= 0) {
                                    listState.animateScrollToItem(last)
                                    // Oxirgi xabar uzun bo'lsa, uning boshida emas, eng pastida tursin.
                                    listState.animateScrollBy(1_000_000f)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = tr("Pastga tushish"),
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            // Bo'sh chat: login sahifasidagi kabi logotip + pastida animatsiyali xabar.
            if (messages.isEmpty() && isModelReady && modelLoadError == null && !isThinking && pendingTool == null) {
                EmptyChatHero(Modifier.fillMaxSize())
            }
        }

        // Working Set: ko'rib chiqilmagan o'zgarishlar (qabul / bekor qilish).
        val pendingForBar = pendingDiffs()
        WorkingSetBar(
            pending = pendingForBar,
            onAcceptAll = { pendingForBar.forEach { acceptDiff(it.id) } },
            onDiscardAll = { pendingForBar.forEach { discardDiff(it.id) } },
            onAccept = { acceptDiff(it) },
            onDiscard = { discardDiff(it) },
            modifier = Modifier.padding(horizontal = 12.dp).padding(top = 6.dp)
        )

        // Kirish paneli: yozish maydoni va tugmalar BITTA umumiy kapsula ichida
        // (login ekranidagi yumaloq maydonlar uslubida). Fokus bo'lsa chegara
        // binafsha, ko'p qatorli matnda tugma pastda turadi.
        var inputFocused by remember { mutableStateOf(false) }
        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingImages.forEach { name ->
                    Box(Modifier.size(64.dp)) {
                        ChatImageThumb(name, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(TextPrimary)
                                .clickable {
                                    pendingImages = pendingImages - name
                                    ImageStore.delete(name)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = tr("Rasmni olib tashlash"), tint = BgApp, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
        // Bepul model tanlangan bo'lsa, tarifsiz ham xabar yuboriladi (faqat 5 soatlik bepul limit bor).
        // Hisoblagich barcha bepul modellar uchun umumiy (server tomonida bitta), shuning uchun limit ham bitta.
        val freeInfo = cloudPlan?.let { cp ->
            cp.freeModels.firstOrNull { it.modelId == onlineCfg?.model } ?: cp.free
        }
        val onFreeModel = isCloud && freeInfo != null && onlineCfg?.model == freeInfo.modelId
        val cloudNoPlan = isCloud && !onFreeModel && cloudPlan != null && cloudPlan?.planId == null && (cloudPlan?.credits ?: 0L) <= 0L
        val freeLimitHit = onFreeModel && freeInfo != null && freeInfo.limit > 0 &&
            freeInfo.used >= freeInfo.limit && freeInfo.resetsAt > System.currentTimeMillis()
        if (freeLimitHit && freeInfo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tr("Bepul limit tugadi. Tiklanadi: %1\$s", java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(freeInfo.resetsAt))),
                    color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentPurple)
                        .clickable { com.mobagent.app.billing.PendingPrompt.put(input); onOpenPlans() }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(tr("Upgrade"), color = BgApp, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        // 90% ogohlantirish: bepul modelda token limiti, pullik tarifda esa 5 soatlik/haftalik/oylik limitdan eng to'ldirilgani.
        val warnRatio: Double
        val warnResetsAt: Long
        val warnPaid: Boolean
        if (onFreeModel && freeInfo != null && freeInfo.limit > 0) {
            warnRatio = freeInfo.used.toDouble() / freeInfo.limit
            warnResetsAt = freeInfo.resetsAt
            warnPaid = false
        } else if (isCloud && cloudPlan?.planId != null) {
            val cp = cloudPlan!!
            val scopes = listOf(cp.windowUsed to cp.windowResetsAt, cp.weekUsed to cp.weekResetsAt, cp.monthUsed to cp.monthResetsAt)
            val top = scopes.maxByOrNull { it.first }!!
            warnRatio = top.first
            warnResetsAt = top.second
            warnPaid = true
        } else {
            warnRatio = 0.0; warnResetsAt = 0L; warnPaid = false
        }
        if (!freeLimitHit && warnRatio >= 0.9 && limitWarnDismissedFor != warnResetsAt) {
            val pct = "${minOf(100, (warnRatio * 100).toInt())}%"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (warnPaid) tr("Tarif limiti deyarli tugadi: %1\$s ishlatildi", pct)
                    else tr("Bepul limit deyarli tugadi: %1\$s ishlatildi", pct),
                    color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentPurple)
                        .clickable { com.mobagent.app.billing.PendingPrompt.put(input); onOpenPlans() }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(tr("Upgrade"), color = BgApp, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    "✕", color = TextSecondary, fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { limitWarnDismissedFor = warnResetsAt }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        if (cloudNoPlan) {
            Text(
                tr("Xabar yuborish uchun tarif tanlang ›"),
                color = AccentPurpleText, fontSize = 12.sp,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 6.dp)
                    .clickable { com.mobagent.app.billing.PendingPrompt.put(input); onOpenPlans() }
            )
        }
        if (showPaywall) {
            AlertDialog(
                onDismissRequest = { showPaywall = false },
                containerColor = BgCardElevated,
                title = { Text(if (freeLimitHit) tr("Bepul limit tugadi") else tr("Tarif kerak"), color = TextPrimary, fontSize = 17.sp) },
                text = {
                    Text(
                        if (freeLimitHit) tr("Bepul tokenlar tugadi. Davom etish uchun tarifni yangilang — yozgan xabaringiz saqlanib turadi.")
                        else tr("Bu modellar pullik tarif orqali ishlaydi. Tarifni tanlang — yozgan xabaringiz saqlanib turadi."),
                        color = TextSecondary, fontSize = 14.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showPaywall = false; onOpenPlans() }) {
                        Text(if (freeLimitHit) tr("Upgrade") else tr("Tariflarni ko'rish"), color = AccentPurpleText)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPaywall = false }) { Text(tr("Bekor qilish"), color = TextSecondary) }
                }
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(BgCard)
                .border(
                    1.dp,
                    if (inputFocused) AccentPurple else BorderColor,
                    RoundedCornerShape(26.dp)
                )
        ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)
            ) {
                if (input.isEmpty()) {
                    Text(tr("Xabar yozing..."), color = TextMuted, fontSize = 14.sp)
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(TextPrimary),
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { inputFocused = it.isFocused }
                )
            }
            if (isThinking) {
                // Generatsiya ketayotganda: bosilsa native siklga to'xtash
                // signali yuboriladi (llama-android.cpp'dagi cancelRequested).
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(TextPrimary)
                        .clickable {
                            GenerationController.cancel(engine)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = tr("To'xtatish"), tint = BgApp, modifier = Modifier.size(17.dp))
                }
            } else if (input.isBlank() && pendingImages.isEmpty()) {
                // Yozish joyi bo'sh: yuborish o'rniga mikrofon tugmasi.
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(TextPrimary)
                        .clickable {
                            // Til ilova tiliga mos (ilgari doim en-US edi — o'zbekcha/ruscha
                            // gapirilganda tanimasdi). Oflayn majburlanmaydi: paket yo'q
                            // bo'lsa tizim o'zi onlayn xizmatga o'tadi.
                            val speechTag = when (AppLanguage.effective) {
                                AppLang.UZ -> "uz-UZ"
                                AppLang.RU -> "ru-RU"
                                AppLang.AR -> "ar-SA"
                                AppLang.EN -> "en-US"
                            }
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechTag)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, tr("Ovoz bilan yozish"))
                            }
                            try {
                                speechLauncher.launch(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, tr("Bu qurilmada ovozni tanish xizmati topilmadi"), Toast.LENGTH_LONG).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = tr("Ovoz bilan yozish"), tint = BgApp, modifier = Modifier.size(19.dp))
                }
            } else {
                val canSend = (input.isNotBlank() || pendingImages.isNotEmpty()) && isModelReady
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (canSend) TextPrimary else BgCardElevated)
                        .clickable(enabled = canSend) {
                            // Pullik rejim: tarif yo'q bo'lsa xabar yuborilmaydi — tarif taklif qilinadi.
                            if (cloudNoPlan || freeLimitHit) {
                                com.mobagent.app.billing.PendingPrompt.put(input)
                                showPaywall = true
                                return@clickable
                            }
                            // Darhol tekshiruv: agar allaqachon bitta so'rov "yo'lda" bo'lsa,
                            // shu joyning o'zida to'xtaymiz — isThinking/canSend
                            // recompozitsiyasini kutmaymiz.
                            if (!sendGuard.compareAndSet(false, true)) return@clickable
                            val prompt = input
                            val sentImages = pendingImages
                            // Model bu bosqichgacha bo'lgan xabarlarni "tarix" sifatida ko'radi
                            // — AgentOrchestrator promptga qo'shib yuboradi (multi-turn context).
                            val historyBefore = messages.flatMap { it.toHistory() }
                            lastUserPrompt = prompt
                            messages = messages + ChatMessage(true, prompt, images = sentImages)
                            input = ""
                            pendingImages = emptyList()
                            isThinking = true
                            streamingText = ""
                            agentMood = AgentMood.THINKING
                            // Ilgari generatsiya faqat ilova old planda ekanida ishonchli
                            // edi — foreground service tizimga jarayonni tez o'ldirmaslikni
                            // bildiradi va davomiyligini doimiy bildirishnoma bilan ko'rsatadi.
                            AgentTaskService.start(context, tr("Javob tayyorlanmoqda..."))
                            scope.launch { persist(messages) }
                            // MUHIM: bu yerda `scope.launch` YO'Q — chaqiruv
                            // GenerationController'ga (process-darajasidagi
                            // singleton) uzatiladi, natija esa yuqoridagi
                            // LaunchedEffect orqali kuzatiladi. Shu bilan
                            // Activity qayta yaratilsa ham javob yo'qolmaydi.
                            GenerationController.startThink(sessionKey, orchestrator, prompt, historyBefore, sentImages)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Send, contentDescription = tr("Yuborish"), tint = if (canSend) BgApp else TextMuted, modifier = Modifier.size(17.dp))
                }
            }
            }
            // Pullik rejim: model tanlash kichik tugmasi yozish maydonining ichida, pastki chap burchakda.
            if (isCloud) {
                val curId = onlineCfg?.model
                val label = cloudPlan?.models?.firstOrNull { it.id == curId }?.name ?: modelName
                Box(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgCardElevated)
                            .clickable(enabled = !isThinking) { cloudMenuOpen = true }
                            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = tr("Modelni tanlash"), tint = TextMuted, modifier = Modifier.size(15.dp))
                    }
                    DropdownMenu(
                        expanded = cloudMenuOpen,
                        onDismissRequest = { cloudMenuOpen = false },
                        // Taxminan 5 ta qator ko'rinadi, qolganlari aylantiriladi (menyu ichida o'zi scroll qiladi).
                        modifier = Modifier.background(BgCardElevated).widthIn(max = 280.dp).heightIn(max = 300.dp)
                    ) {
                        val models = cloudPlan?.models.orEmpty()
                        if (models.isEmpty()) {
                            DropdownMenuItem(text = { Text(tr("Yuklanmoqda..."), color = TextMuted, fontSize = 13.sp) }, onClick = {}, enabled = false)
                        }
                        models.sortedWith(compareBy({ !it.free }, { it.tier != "medium" })).forEach { m ->
                            // Tarifsiz foydalanuvchi hamma modelni ko'ra va tanlay oladi; qulf faqat tarifi pastroq bo'lsa.
                            val lockedForUser = (cloudPlan?.planId != null || (cloudPlan?.credits ?: 0L) > 0L) && m.locked
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(m.name, color = if (lockedForUser) TextMuted else TextPrimary, fontSize = 14.sp)
                                            if (m.free) {
                                                Text(tr("Bepul"), color = AccentPurpleText, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                                            }
                                            if (lockedForUser) {
                                                Icon(Icons.Filled.Lock, contentDescription = null, tint = TextMuted, modifier = Modifier.padding(start = 8.dp).size(13.dp))
                                            } else if (m.id == curId) {
                                                Icon(Icons.Filled.Check, contentDescription = null, tint = AccentPurpleText, modifier = Modifier.padding(start = 8.dp).size(15.dp))
                                            }
                                        }
                                        Text(
                                            tr(cloudModelNote(m.id, m.tier)),
                                            color = TextMuted, fontSize = 11.sp, lineHeight = 14.sp,
                                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                onClick = {
                                    cloudMenuOpen = false
                                    when {
                                        lockedForUser -> { com.mobagent.app.billing.PendingPrompt.put(input); onOpenPlans() }
                                        m.id != curId -> onSwitchCloudModel(m.id, m.name)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

/** Model tanlash menyusidagi qisqa izoh (o'zbekcha kalit; tr() bilan tarjima qilinadi). */
private fun cloudModelNote(id: String, tier: String): String = when {
    id.contains("o3-mini") -> "Mantiq va hisob-kitob uchun mo'ljallangan"
    listOf("opus", "fable", "astra", "gpt-5-5", "gpt-5-6-sol").any { id.contains(it) } ->
        "Eng kuchli: chuqur tahlil va katta loyihalar uchun, limitni eng tez sarflaydi"
    tier == "medium" -> "Tez va tejamkor: oddiy suhbatlar uchun qulay"
    else -> "Kod yozish va murakkab vazifalar uchun, lekin limitni ko'proq sarflaydi"
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    family: ModelFamily?,
    canEdit: Boolean = false,
    isEditing: Boolean = false,
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onConfirmEdit: (String) -> Unit = {}
) {
    when {
        // Foydalanuvchi: o'ngda, yumaloq pufakcha (pastki o'ng burchak kichikroq — "dum" effekti).
        msg.fromUser -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (isEditing) {
                // Joyida tahrirlash: matn maydoni + Bekor qilish / Yuborish.
                var draft by remember(msg.text) {
                    mutableStateOf(TextFieldValue(msg.text, TextRange(msg.text.length)))
                }
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                val canSubmit = draft.text.isNotBlank()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(BgCard)
                        .border(1.dp, AccentPurple, RoundedCornerShape(20.dp))
                        .padding(14.dp)
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(TextPrimary),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            tr("Bekor qilish"),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(onClick = onCancelEdit)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr("Yuborish"),
                            color = if (canSubmit) BgApp else TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (canSubmit) TextPrimary else BgCardElevated)
                                .clickable(enabled = canSubmit) { onConfirmEdit(draft.text) }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            } else {
                if (msg.images.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(0.82f).padding(bottom = if (msg.text.isBlank()) 0.dp else 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)
                    ) {
                        msg.images.forEach { name ->
                            ChatImageThumb(name, Modifier.size(if (msg.images.size == 1) 180.dp else 88.dp).clip(RoundedCornerShape(14.dp)))
                        }
                    }
                }
                if (msg.text.isNotBlank()) Box(
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .wrapContentWidth(Alignment.End)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                        .background(TextPrimary)
                        .padding(horizontal = 16.dp, vertical = 11.dp)
                ) {
                    // SelectionContainer: o'z xabaringizdan ham matn belgilab nusxalash mumkin.
                    SelectionContainer {
                        Text(msg.text, color = BgApp, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                if (msg.text.isNotBlank()) {
                    UserMessageActions(
                        text = msg.text,
                        modifier = Modifier.padding(top = 2.dp),
                        onEdit = if (canEdit) onStartEdit else null
                    )
                }
            }
        }
        // Eski saqlangan terminal natijasi: pufaksiz, lekin kod-karta ko'rinishida.
        msg.isTerminal -> {
            val (header, body) = splitTerminalMessage(msg.text)
            Column(Modifier.fillMaxWidth()) {
                Text(header, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                CodeBlock(lang = null, code = body)
            }
        }
        // AI javobi: fonsiz, chegarasiz, butun kenglikda — matn to'g'ridan-to'g'ri sahifada.
        else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (SHOW_AI_AVATAR) {
                AgentAvatar(family, size = 24.dp, iconSize = 13.dp, neutral = true)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                MarkdownText(msg.text, modifier = Modifier.fillMaxWidth(), baseFontSize = 14.sp)
                if (msg.text.isNotBlank()) {
                    MessageActions(msg.text, Modifier.padding(top = 4.dp))
                    AiDisclaimer(Modifier.padding(top = 2.dp, bottom = 2.dp))
                }
            }
        }
    }
}

private fun splitTerminalMessage(text: String): Pair<String, String> {
    val idx = text.indexOf('\n')
    return if (idx == -1) text to "" else text.substring(0, idx) to text.substring(idx + 1)
}

/** Chatdagi rasm eskizi (ImageStore'dan fonda o'qiladi). Fayl yo'q bo'lsa — kulrang joy. */
@Composable
private fun ChatImageThumb(name: String, modifier: Modifier = Modifier) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.IO) { ImageStore.thumbnail(name)?.asImageBitmap() }
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(BgCardElevated))
    }
}

/** Onlayn model ro'yxatini kalit bilan yuklab, [OnlineModelSwitcherPopup]ni ko'rsatadi. */
@Composable
private fun ModelSwitcherPopupOnlineHost(
    cfg: OnlineChatConfig,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onSelect: (String) -> Unit
) {
    val context = LocalContext.current
    val provider = cfg.provider ?: return
    OnlineModelSwitcherPopup(
        providerName = provider.name,
        loader = {
            val key = ApiKeyStore.get(context, provider.id).orEmpty()
            com.mobagent.app.online.OnlineApi.withCustom(
                com.mobagent.app.online.OnlineApi.listModels(provider, key),
                ApiKeyStore.getCustomModels(context, provider.id)
            )
        },
        currentModel = cfg.model,
        onDismiss = onDismiss,
        onManage = onManage,
        onSelect = onSelect
    )
}
