package com.mobagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.mobagent.app.i18n.tr
import com.mobagent.app.online.ApiKeyStore
import com.mobagent.app.online.CloudKeys
import com.mobagent.app.online.KeyNote
import com.mobagent.app.online.KeyVerdict
import com.mobagent.app.online.ModelCheck
import com.mobagent.app.online.OnlineApi
import com.mobagent.app.online.OnlineProvider
import com.mobagent.app.online.OnlineProviders
import com.mobagent.app.ui.theme.*

/**
 * Onlayn rejim → "O'z API kalitim bilan": 12 ta xizmat uchun API kalit kiritiladigan sahifa.
 * Kalit xizmatning o'zida (uning hujjatidagi so'rov bilan) tekshiriladi; faqat to'g'ri kalit saqlanadi.
 * Kalitlar qurilmada shifrlangan holda saqlanadi.
 */
@Composable
fun ApiKeysScreen(
    onBack: () -> Unit,
    onConfirm: (OnlineChoice) -> Unit
) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }          // saqlash/o'chirishdan keyin qayta o'qish uchun
    var expandedId by remember { mutableStateOf<String?>(null) }
    val saved = remember(version, com.mobagent.app.auth.AuthRepository.user?.uid) { OnlineProviders.all.associate { it.id to ApiKeyStore.has(context, it.id) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Text(
            tr("‹ Orqaga"), color = TextSecondary, fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onBack)
        )
        Spacer(Modifier.height(16.dp))
        Text(tr("API kalitlar"), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(
            tr("Xizmatni tanlang va kalitingizni kiriting. Kalit xizmatda tekshiriladi va qurilmada shifrlangan holda saqlanadi."),
            color = TextSecondary, fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KeySyncCard(onChanged = { version++ })
            OnlineProviders.all.forEach { provider ->
                ProviderCard(
                    provider = provider,
                    hasKey = saved[provider.id] == true,
                    expanded = expandedId == provider.id,
                    onToggle = { expandedId = if (expandedId == provider.id) null else provider.id },
                    onSaved = { version++ },
                    onUse = { model -> onConfirm(OnlineChoice.OwnApiKey(provider.id, model)) }
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Kartadagi tekshiruv holati. */
private sealed class CheckState {
    object Idle : CheckState()
    object Checking : CheckState()
    /** Kalit tasdiqlandi; model tanlash ko'rsatiladi. */
    data class Verified(val models: List<String>, val note: KeyNote?, val unverified: Boolean = false) : CheckState()
    /** [canForce] — internet/server muammosi: kalit haqida hech narsa deya olmaymiz, foydalanuvchi o'zi qaror qiladi. */
    data class Failed(val message: String, val canForce: Boolean) : CheckState()
}

@Composable
private fun ProviderCard(
    provider: OnlineProvider,
    hasKey: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSaved: () -> Unit,
    onUse: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saqlangan kalit bo'lsa, kiritish maydonida shu kalitning o'zi turadi (sukut bo'yicha yashirin, "Ko'rsatish" bilan ochiladi).
    var input by remember(provider.id, expanded) {
        mutableStateOf(if (expanded && hasKey) ApiKeyStore.get(context, provider.id).orEmpty() else "")
    }
    var visible by remember(provider.id, expanded) { mutableStateOf(false) }
    // Yozuv bor, lekin ochib bo'lmadi (get() uni tozalagan bo'lishi mumkin) — ro'yxat holatini yangilaymiz.
    LaunchedEffect(provider.id, expanded, hasKey) {
        if (expanded && hasKey && input.isEmpty()) onSaved()
    }
    var saveFailed by remember(provider.id, expanded) { mutableStateOf(false) }
    var state by remember(provider.id, expanded) { mutableStateOf<CheckState>(CheckState.Idle) }
    var selectedModel by remember(provider.id, expanded) { mutableStateOf<String?>(null) }
    var manualModel by remember(provider.id, expanded) { mutableStateOf("") }
    val shape = RoundedCornerShape(14.dp)

    // Qo'lda qo'shilgan modellar (faqat allowCustomModels xizmatlar: OpenRouter, Gemini).
    var customModels by remember(provider.id, expanded) {
        mutableStateOf(if (provider.allowCustomModels) ApiKeyStore.getCustomModels(context, provider.id) else emptyList())
    }
    var addInput by remember(provider.id, expanded) { mutableStateOf("") }
    var addBusy by remember(provider.id, expanded) { mutableStateOf(false) }
    var addError by remember(provider.id, expanded) { mutableStateOf<String?>(null) }

    // Tekshiriladigan kalit: yangi kiritilgan, bo'lmasa saqlangani.
    fun keyToUse(): String? = input.trim().ifEmpty { ApiKeyStore.get(context, provider.id) }

    fun verified(key: String, v: KeyVerdict.Valid) {
        // Yangi kiritilgan kalit faqat TO'G'RI bo'lgandan keyin saqlanadi.
        if (input.isNotBlank() && !ApiKeyStore.set(context, provider.id, key)) {
            saveFailed = true
            return
        }
        onSaved()
        val remembered = ApiKeyStore.getModel(context, provider.id)
        val merged = OnlineApi.withCustom(v.models, customModels).orEmpty()
        selectedModel = remembered?.takeIf { merged.isEmpty() || it in merged } ?: v.defaultModel
        state = CheckState.Verified(v.models, v.note)
    }

    fun startCheck() {
        val key = keyToUse() ?: return
        saveFailed = false
        state = CheckState.Checking
        scope.launch {
            when (val v = OnlineApi.verify(provider, key)) {
                is KeyVerdict.Valid -> verified(key, v)
                is KeyVerdict.Invalid -> {
                    val wrongPrefix = provider.keyPrefix?.let { !key.startsWith(it) } == true
                    val base = if (wrongPrefix) tr("Kalit noto'g'ri. %1\$s kaliti odatda «%2\$s» bilan boshlanadi.", provider.name, provider.keyPrefix)
                    else tr("Kalit noto'g'ri yoki bekor qilingan.")
                    // Xizmatning o'z izohi (masalan "no credits", "restricted") — sababni aniqlash uchun.
                    val withDetail = v.detail?.takeIf { it.isNotBlank() }?.let { "$base\n(${it.take(300)})" } ?: base
                    // Perplexity'da 401 kalit noto'g'ri bo'lganda ham, hisobda kredit tugaganda ham qaytadi.
                    val msg = if (provider.id == "perplexity" && !wrongPrefix)
                        withDetail + "\n" + tr("Perplexity 401: kalit noto'g'ri yoki o'chirilgan, yoki hisobda kredit tugagan. console.perplexity.ai/project/billing sahifasini tekshiring.")
                    else withDetail
                    state = CheckState.Failed(msg, canForce = false)
                }
                is KeyVerdict.NoNetwork ->
                    state = CheckState.Failed(tr("Internetga ulanib bo'lmadi. Tarmoqni tekshiring."), canForce = true)
                is KeyVerdict.Unknown ->
                    state = CheckState.Failed(tr("Xizmat vaqtincha javob bermayapti (%1\$s). Keyinroq urinib ko'ring.", v.code), canForce = true)
            }
        }
    }

    /** Tekshirib bo'lmaganda (internet yo'q): foydalanuvchi o'z javobgarligi bilan davom etadi. */
    fun forceSave() {
        val key = keyToUse() ?: return
        if (input.isNotBlank() && !ApiKeyStore.set(context, provider.id, key)) { saveFailed = true; return }
        onSaved()
        val model = ApiKeyStore.getModel(context, provider.id)
            ?: OnlineApi.pickDefault(provider, OnlineApi.fallbackModels(provider))
        state = CheckState.Verified(OnlineApi.fallbackModels(provider), null, unverified = true)
        selectedModel = model
    }

    /** Tanlangan modelni eslab, chatni shu model bilan ochadi. */
    fun useModel(model: String) {
        ApiKeyStore.setModel(context, provider.id, model)
        onUse(model)
    }

    /**
     * Model nomini qo'shadi: ro'yxatda bo'lsa shunchaki tanlaydi; yo'q bo'lsa xizmatda tekshirib, saqlaydi va tanlaydi.
     * [thenUse] — qo'shish muvaffaqiyatli bo'lsa, darhol chatni shu model bilan ochadi ("Davom etish" bosilganda).
     */
    fun addCustom(thenUse: Boolean = false) {
        if (addBusy) return
        val id = OnlineApi.normalizeModelId(provider, addInput)
        if (id == null) {
            addError = if (provider.id == "openrouter") tr("Model nomi noto'g'ri. Format: muallif/model, masalan openai/gpt-4o")
            else tr("Model nomi noto'g'ri. Masalan: %1\$s", provider.customModelExample)
            return
        }
        val apiModels = (state as? CheckState.Verified)?.models.orEmpty()
        if (id in OnlineApi.withCustom(apiModels, customModels).orEmpty()) {
            selectedModel = id; addInput = ""; addError = null
            if (thenUse) useModel(id)
            return
        }
        val key = keyToUse() ?: return
        addBusy = true
        addError = null
        scope.launch {
            if (OnlineApi.checkModel(provider, key, id) == ModelCheck.NOT_FOUND) {
                addError = if (provider.id == "openrouter") tr("OpenRouter'da bunday model topilmadi. Nomini openrouter.ai/models sahifasidan nusxalang.")
                else tr("Bunday model topilmadi.")
                addBusy = false
            } else {
                ApiKeyStore.addCustomModel(context, provider.id, id)
                customModels = ApiKeyStore.getCustomModels(context, provider.id)
                selectedModel = id
                addInput = ""
                addBusy = false
                if (thenUse) useModel(id)
            }
        }
    }

    fun removeCustom(m: String) {
        ApiKeyStore.removeCustomModel(context, provider.id, m)
        customModels = ApiKeyStore.getCustomModels(context, provider.id)
        if (selectedModel == m) {
            selectedModel = (state as? CheckState.Verified)?.let { OnlineApi.pickDefault(provider, it.models) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(BgCard)
            .border(0.5.dp, if (expanded) AccentPurple else BorderColor, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                provider.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (hasKey) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentPurpleBg)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(tr("Saqlangan"), color = AccentPurpleText, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(if (expanded) "⌃" else "⌄", color = TextMuted, fontSize = 14.sp)
        }

        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                val verifiedState = state as? CheckState.Verified
                if (verifiedState == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgCardAlt)
                            .border(0.5.dp, BorderColor, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                if (hasKey) tr("Yangi kalit (bo'sh qoldirsangiz, saqlangani ishlatiladi)") else tr(provider.keyHint),
                                color = TextMuted, fontSize = 12.sp
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it; saveFailed = false; if (state is CheckState.Failed) state = CheckState.Idle },
                            singleLine = true,
                            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
                            cursorBrush = SolidColor(AccentPurple),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            provider.keyPage, color = TextMuted, fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (visible) tr("Yashirish") else tr("Ko'rsatish"),
                            color = TextSecondary, fontSize = 11.sp,
                            modifier = Modifier.clickable { visible = !visible }
                        )
                    }
                }
                if (saveFailed) {
                    Spacer(Modifier.height(6.dp))
                    Text(tr("Kalitni saqlab bo'lmadi. Qayta urinib ko'ring."), color = DangerText, fontSize = 12.sp)
                }
                (state as? CheckState.Failed)?.let { f ->
                    Spacer(Modifier.height(6.dp))
                    Text(f.message, color = DangerText, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))

                if (verifiedState != null) {
                    VerifiedPanel(
                        provider = provider,
                        verified = verifiedState.copy(models = OnlineApi.withCustom(verifiedState.models, customModels).orEmpty()),
                        customModels = customModels,
                        addInput = addInput,
                        onAddInputChange = { addInput = it; addError = null },
                        addBusy = addBusy,
                        addError = addError,
                        onAdd = { addCustom() },
                        onRemoveCustom = { removeCustom(it) },
                        selected = selectedModel,
                        manualModel = manualModel,
                        onManualChange = { manualModel = it; if (it.isNotBlank()) selectedModel = it.trim() },
                        onSelect = { selectedModel = it; addInput = ""; addError = null },
                        onContinue = {
                            if (provider.allowCustomModels && addInput.isNotBlank()) {
                                // Foydalanuvchi model nomini yozib «Qo'shish»ni bosmagan bo'lsa ham — o'zi qo'shiladi va ishlatiladi.
                                addCustom(thenUse = true)
                            } else {
                                selectedModel?.takeIf { it.isNotBlank() }?.let { useModel(it) }
                            }
                        }
                    )
                } else {
                    val checking = state is CheckState.Checking
                    val canCheck = !checking && (input.isNotBlank() || hasKey)
                    val failed = state as? CheckState.Failed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                checking -> tr("Tekshirilmoqda...")
                                failed?.canForce == true -> tr("Qayta tekshirish ›")
                                else -> tr("Tekshirish va davom etish ›")
                            },
                            color = if (canCheck) AccentPurpleText else TextMuted,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable(enabled = canCheck) { startCheck() }
                        )
                        if (failed?.canForce == true) {
                            Spacer(Modifier.width(20.dp))
                            Text(
                                tr("Tekshirmasdan saqlash"), color = TextSecondary, fontSize = 13.sp,
                                modifier = Modifier.clickable { forceSave() }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (hasKey && !checking) {
                            Text(
                                tr("O'chirish"), color = DangerText, fontSize = 13.sp,
                                modifier = Modifier.clickable {
                                    ApiKeyStore.remove(context, provider.id)
                                    input = ""
                                    onSaved()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Kalit tasdiqlangach: holat izohi, model ro'yxati va "Davom etish". */
@Composable
private fun VerifiedPanel(
    provider: OnlineProvider,
    verified: CheckState.Verified,
    customModels: List<String>,
    addInput: String,
    onAddInputChange: (String) -> Unit,
    addBusy: Boolean,
    addError: String?,
    onAdd: () -> Unit,
    onRemoveCustom: (String) -> Unit,
    selected: String?,
    manualModel: String,
    onManualChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onContinue: () -> Unit
) {
    Text(
        if (verified.unverified || (verified.models.isEmpty() && verified.note == null)) tr("Kalit saqlandi") else tr("Kalit to'g'ri ✓"),
        color = AccentPurpleText, fontSize = 13.sp, fontWeight = FontWeight.Medium
    )
    when (verified.note) {
        KeyNote.RATE_LIMITED -> Text(tr("Kalit to'g'ri, lekin hozir so'rovlar limiti tugagan."), color = TextSecondary, fontSize = 12.sp)
        KeyNote.NO_CREDIT -> Text(tr("Kalit to'g'ri, lekin hisobda mablag' yo'q."), color = TextSecondary, fontSize = 12.sp)
        null -> {}
    }
    Spacer(Modifier.height(10.dp))
    Text(tr("Modelni tanlang"), color = TextSecondary, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))

    if (verified.models.isNotEmpty()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BgCardAlt)
                .border(0.5.dp, BorderColor, RoundedCornerShape(8.dp))
        ) {
            items(verified.models) { m ->
                val isSel = m == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(m) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        m, color = if (isSel) AccentPurpleText else TextPrimary, fontSize = 12.sp,
                        fontWeight = if (isSel) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSel) Text("✓", color = AccentPurpleText, fontSize = 12.sp)
                    if (m in customModels) {
                        Text(
                            "✕", color = TextMuted, fontSize = 13.sp,
                            modifier = Modifier.clickable { onRemoveCustom(m) }.padding(start = 14.dp)
                        )
                    }
                }
            }
        }
    } else if (provider.allowCustomModels) {
        Text(tr("Ro'yxat olinmadi — model nomini pastda qo'lda kiriting."), color = TextMuted, fontSize = 12.sp)
    } else {
        // Ro'yxat olinmadi (limit/internet): model nomini qo'lda kiritish mumkin.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(BgCardAlt)
                .border(0.5.dp, BorderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (manualModel.isEmpty()) {
                Text(selected ?: tr("Model nomi"), color = TextMuted, fontSize = 12.sp)
            }
            BasicTextField(
                value = manualModel,
                onValueChange = onManualChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
                cursorBrush = SolidColor(AccentPurple),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (provider.allowCustomModels) {
        Spacer(Modifier.height(12.dp))
        Text(
            if (provider.id == "openrouter") tr("Model qo'shish (OpenRouter'dagi nomi)") else tr("Model qo'shish (nomi)"),
            color = TextSecondary, fontSize = 12.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgCardAlt)
                    .border(0.5.dp, BorderColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (addInput.isEmpty()) {
                    Text(
                        if (provider.id == "openrouter") tr("Masalan: openai/gpt-4o")
                        else tr("Masalan: %1\$s", provider.customModelExample),
                        color = TextMuted, fontSize = 12.sp, maxLines = 1
                    )
                }
                BasicTextField(
                    value = addInput,
                    onValueChange = onAddInputChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAdd() }),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
                    cursorBrush = SolidColor(AccentPurple),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(12.dp))
            val canAdd = addInput.isNotBlank() && !addBusy
            Text(
                if (addBusy) tr("Tekshirilmoqda...") else tr("Qo'shish ›"),
                color = if (canAdd) AccentPurpleText else TextMuted,
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = canAdd, onClick = onAdd)
            )
        }
        addError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = DangerText, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
    val canGo = (!selected.isNullOrBlank() || (provider.allowCustomModels && addInput.isNotBlank())) && !addBusy
    Text(
        tr("Davom etish ›"),
        color = if (canGo) AccentPurpleText else TextMuted,
        fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable(enabled = canGo, onClick = onContinue)
    )
}


/**
 * Kalitlar hisobga avtomatik sinxronlanadi (CloudKeys): sahifa ochilganda bulutdan yangilarini oladi.
 */
@Composable
private fun KeySyncCard(onChanged: () -> Unit) {
    val context = LocalContext.current
    val uid = com.mobagent.app.auth.AuthRepository.user?.uid ?: return // mehmon rejimida hisob yo'q
    LaunchedEffect(uid) {
        if (CloudKeys.sync(context) > 0) onChanged()
    }
    Text(
        tr("Kalitlar hisobingizga shifrlab saqlanadi va boshqa telefonlarda avtomatik chiqadi."),
        color = TextMuted, fontSize = 11.sp
    )
}
