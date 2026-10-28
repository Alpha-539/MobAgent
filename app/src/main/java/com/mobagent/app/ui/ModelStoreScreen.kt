package com.mobagent.app.ui

import com.mobagent.app.i18n.mirrorInRtl
import com.mobagent.app.i18n.tr
import android.app.ActivityManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import com.mobagent.app.auth.AuthRepository
import com.mobagent.app.model.ApiClient
import com.mobagent.app.model.RemoteCatalog
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.R
import com.mobagent.app.model.CustomModelStore
import com.mobagent.app.model.ModelBadge
import com.mobagent.app.model.ModelCatalog
import com.mobagent.app.model.ModelCategory
import com.mobagent.app.model.ModelDownloadService
import com.mobagent.app.model.isMeteredNetwork
import com.mobagent.app.model.ModelDownloadState
import com.mobagent.app.model.ModelItem
import com.mobagent.app.model.ModelTier
import com.mobagent.app.model.STANDARD_CTX
import com.mobagent.app.model.DOWNLOAD_SPACE_MARGIN_BYTES
import com.mobagent.app.model.freeSpaceBytes
import com.mobagent.app.model.modelsDir
import com.mobagent.app.model.partialDownloadBytes
import com.mobagent.app.model.deleteModelPartials
import com.mobagent.app.model.deleteDownloadedModel
import com.mobagent.app.model.downloadedModelFile
import com.mobagent.app.ui.theme.*
import java.io.File
import kotlin.math.ceil

/**
 * Ilgari bu ekran diskni hech qachon tekshirmasdi, shuning uchun avval
 * yuklab olingan model ham har safar qaytadan "Yuklab olish (N MB)" bilan
 * ko'rinardi. Endi har bir model uchun fayl mavjudligini tekshirib,
 * "Ochish" (mavjud bo'lsa) yoki "Yuklab olish" (bo'lmasa) ko'rsatamiz.
 */
@Composable
fun ModelStoreScreen(
    onBack: () -> Unit,
    onModelSelected: (ModelItem) -> Unit,
    onModelOpen: (ModelItem, File) -> Unit,
    onMenuClick: (() -> Unit)? = null,
    // DownloadScreen'da yuklash xato bilan tugasa, shu yerda ko'rsatiladi.
    startupError: String? = null,
    onStartupErrorShown: () -> Unit = {},
    onAddClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var models by remember { mutableStateOf(ModelCatalog.models) }
    val scope = rememberCoroutineScope()
    var showAddCustomDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ModelCatalog.custom = CustomModelStore.load(context)
        models = ModelCatalog.models
    }
    // Yulduzchalar: men qo'yganlar va (o'zim o'zgartirgandan keyingi) yangi umumiy sonlar
    var myStars by remember { mutableStateOf<Set<String>>(emptySet()) }
    var starCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var starBusy by remember { mutableStateOf(false) }
    // Do'kon ochilganda katalog serverdan yangilanadi (xato bo'lsa — keshdagi/o'rnatilgan ro'yxat qoladi).
    LaunchedEffect(AuthRepository.user?.uid) {
        if (RemoteCatalog.refresh(context)) models = ModelCatalog.models
        ApiClient.myStars()?.let { myStars = it }
    }
    var errorMsg by remember { mutableStateOf(startupError) }
    LaunchedEffect(startupError) {
        if (startupError != null) onStartupErrorShown()
    }
    // Karta ustidagi o'chirish tugmasi tasdiq so'raydi (tasodifan bosilib ketmasligi uchun).
    var toDelete by remember { mutableStateOf<ModelItem?>(null) }
    // RAM yetmasligi mumkin bo'lgan modelni ochishdan oldin ogohlantirish.
    var pendingOpen by remember { mutableStateOf<Pair<ModelItem, File>?>(null) }
    // Mobil internetda yuklashdan oldin tasdiq.
    var pendingMetered by remember { mutableStateOf<ModelItem?>(null) }

    var downloadedVersion by remember { mutableStateOf(0) } // o'chirilgandan keyin qayta tekshirish uchun

    // Faol yuklash: faqat foiz o'zgarganda yangilanadi (har bayt emas) — ro'yxat qotib qolmasligi uchun.
    val activeDownload by remember {
        ModelDownloadService.state.map { it.toActive() }.distinctUntilChanged()
    }.collectAsState(initial = ModelDownloadService.state.value.toActive())

    // Do'konda turgan paytda yuklash tugasa/xato bo'lsa — ro'yxatni yangilaymiz / xatoni ko'rsatamiz.
    LaunchedEffect(Unit) {
        ModelDownloadService.state.collect { st ->
            when (st) {
                is ModelDownloadState.Success -> {
                    downloadedVersion++
                    ModelDownloadService.consume()
                }
                is ModelDownloadState.Error -> {
                    errorMsg = st.message
                    ModelDownloadService.consume()
                }
                else -> {}
            }
        }
    }
    val downloadedFiles = remember(downloadedVersion, models) {
        models.associate { it.id to downloadedModelFile(context, it.id) }
    }
    // Yuklab olingan model fayllari kodning o'zi tomonidan hech qachon
    // o'chirilmaydi (doimiy papkada saqlanadi). Lekin ba'zi telefonlar
    // (Xiaomi/MIUI, Huawei, Oppo/Vivo va h.k.) ilova "band" deb
    // hisoblanmasa, uning ma'lumotlarini agressiv tozalab qo'yishi mumkin —
    // shuning uchun bu ixtiyoriy himoya banneri.
    // null = "Hammasi" (filtrsiz)
    var filter by remember { mutableStateOf<ModelCategory?>(null) }
    // true = faqat yuklab olingan (installed) modellar. Kategoriya filtri bilan birga ishlaydi.
    var onlyInstalled by remember { mutableStateOf(false) }
    // true = faqat hozir yuklanayotgan yoki pauzada/yarim to'xtagan modellar
    var onlyDownloading by remember { mutableStateOf(false) }
    var toDeletePartial by remember { mutableStateOf<ModelItem?>(null) }
    // Telefonning umumiy RAM'i (GB) — kartadagi "og'ir" belgisi uchun.
    val totalGb = remember { totalRamGb(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onBack)) {
                Icon(Icons.Filled.ArrowBackIosNew, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp).mirrorInRtl())
                Spacer(Modifier.width(6.dp))
                Text(tr("Orqaga"), color = TextSecondary, fontSize = 13.sp)
            }
            if (onMenuClick != null) {
                Icon(
                    Icons.Filled.Menu, contentDescription = tr("Menyu"), tint = TextSecondary,
                    modifier = Modifier.size(22.dp).clickable(onClick = onMenuClick)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(tr("Modelni tanlang"), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(Sizing.radiusPill))
                    .background(AccentPurpleBg)
                    .border(1.dp, AccentPurple, RoundedCornerShape(Sizing.radiusPill))
                    .clickable { onAddClick?.invoke() ?: run { showAddCustomDialog = true } }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("Qo'shish"), color = AccentPurpleText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(tr("Har biri turli vazifaga mos, kerakli xotira va RAM ko'rsatilgan"), color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))


        errorMsg?.let {
            Text(it, color = DangerText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }

        // MUHIM TUZATISH: ilgari ro'yxat doim ModelCatalog'dagi qattiq
        // tartibda chizilardi — foydalanuvchi allaqachon yuklab olgan
        // model ro'yxat o'rtasida yoki oxirida "ko'milib" qolishi mumkin
        // edi. Endi yuklab olingan modellar (downloadedFiles'da fayli bor)
        // doim ro'yxat BOSHIDA chiqadi — Kotlin'ning sortedByDescending'i
        // BARQAROR (stable) tartiblash, shuning uchun har guruh ichida
        // katalogdagi asl tartib saqlanadi (yuklab olinmagan modellar
        // o'zaro, yuklab olinganlar o'zaro — asl ketma-ketlikda).
        val sortedModels = remember(models, downloadedFiles, activeDownload, downloadedVersion) {
            models.sortedWith(
                compareByDescending<ModelItem> { downloadedFiles[it.id] != null }
                    .thenByDescending { activeDownload?.modelId == it.id || partialDownloadBytes(context, it.id) > 0 }
            )
        }

        val downloadingCount = remember(models, downloadedFiles, activeDownload, downloadedVersion) {
            models.count { model ->
                downloadedFiles[model.id] == null &&
                    (activeDownload?.modelId == model.id || partialDownloadBytes(context, model.id) > 0)
            }
        }

        // Filtr tugmalari: Hammasi + Yuklab olingan + Yuklanayotgan + kategoriyalar
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                text = tr("Hammasi"),
                selected = filter == null && !onlyInstalled && !onlyDownloading,
                onClick = { filter = null; onlyInstalled = false; onlyDownloading = false }
            )
            FilterChip(
                text = tr("Yuklab olingan"),
                selected = onlyInstalled,
                onClick = { onlyInstalled = !onlyInstalled; onlyDownloading = false }
            )
            val downloadingBadge = if (downloadingCount > 0) " ($downloadingCount)" else ""
            FilterChip(
                text = tr("Yuklanayotgan") + downloadingBadge,
                selected = onlyDownloading,
                onClick = { onlyDownloading = !onlyDownloading; onlyInstalled = false }
            )
            ModelCategory.values().forEach { cat ->
                FilterChip(
                    text = cat.label,
                    selected = filter == cat && !onlyDownloading,
                    onClick = { filter = cat; onlyDownloading = false }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        val visible = sortedModels.filter { model ->
            if (onlyDownloading) {
                downloadedFiles[model.id] == null &&
                    (activeDownload?.modelId == model.id || partialDownloadBytes(context, model.id) > 0)
            } else if (onlyInstalled) {
                downloadedFiles[model.id] != null && (filter == null || model.category == filter)
            } else {
                (filter == null || model.category == filter)
            }
        }
        // Bo'limlar tartibi: Tezkor -> Agent -> Kuchli. Bo'sh bo'lim ko'rsatilmaydi.
        val sections = ModelTier.values().map { tier -> tier to visible.filter { it.tier == tier } }
            .filter { it.second.isNotEmpty() }

        if (sections.isEmpty()) {
            Text(
                when {
                    onlyDownloading -> tr("Hozirda yuklanayotgan yoki to'xtatilgan modellar yo'q.")
                    onlyInstalled && filter == null -> tr("Hali yuklab olingan model yo'q")
                    else -> tr("Bu filtrga mos model yo'q")
                },
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sections.forEach { (tier, tierModels) ->
                item(key = "header-${tier.name}") {
                    TierHeader(tier)
                }
                items(tierModels, key = { it.id }) { model ->
                    val file = downloadedFiles[model.id]
                    ModelCard(
                        model = model,
                        stars = starCounts[model.id] ?: model.stars,
                        starred = model.id in myStars,
                        // Faqat kirgan, emaili tasdiqlangan va modelni yuklab olgan foydalanuvchi baho beradi.
                        canStar = file != null && AuthRepository.user != null && AuthRepository.emailVerified,
                        onStarClick = {
                            if (!starBusy) {
                                starBusy = true
                                val on = model.id !in myStars
                                scope.launch {
                                    val n = ApiClient.setStar(model.id, on)
                                    if (n != null) {
                                        myStars = if (on) myStars + model.id else myStars - model.id
                                        starCounts = starCounts + (model.id to n)
                                    } else {
                                        errorMsg = tr("Yulduzchani saqlab bo'lmadi. Internetni tekshiring.")
                                    }
                                    starBusy = false
                                }
                            }
                        },
                        downloaded = file != null,
                        partialBytes = partialDownloadBytes(context, model.id),
                        tooHeavy = !fitsTotalRam(totalGb, model),
                        recommendedGb = if (model.paramsBillion >= 3.0) recommendedRamGb(model) else null,
                        activeDownload = activeDownload?.takeIf { it.modelId == model.id },
                        onViewDownload = { onModelSelected(model) },
                        onDownloadClick = dl@{
                            // Bir vaqtda bitta yuklash (bitta servis/bildirishnoma).
                            val other = activeDownload
                            if (other != null && !other.paused && other.modelId != model.id) {
                                errorMsg = tr("Boshqa model yuklanmoqda. Avval uni tugating yoki bekor qiling.")
                                return@dl
                            }
                            // Avval bo'sh joy: qolgan hajm (yarim fayl bo'lsa, uni hisobga olib) + zaxira.
                            val needBytes = model.totalDownloadSizeMb * 1024L * 1024L -
                                partialDownloadBytes(context, model.id) + DOWNLOAD_SPACE_MARGIN_BYTES
                            val freeBytes = freeSpaceBytes(modelsDir(context))
                            if (freeBytes < needBytes) {
                                errorMsg = tr(
                                    "Xotirada bo'sh joy yetarli emas: kerak %1\$s MB, bor %2\$s MB.",
                                    needBytes / (1024L * 1024L), freeBytes / (1024L * 1024L)
                                )
                            } else if (fitsTotalRam(totalGb, model)) {
                                errorMsg = null
                                if (isMeteredNetwork(context)) pendingMetered = model
                                else onModelSelected(model)
                            } else {
                                errorMsg = if (model.contextSize > STANDARD_CTX)
                                    tr("Xotira yetarli emas. Kichikroq model tanlang yoki Sozlamalarda kontekstni kamaytiring.")
                                else tr("Xotira yetarli emas, kichikroq modelni tanlang")
                            }
                        },
                        onCancelPartial = { toDeletePartial = model },
                        onOpenClick = {
                            // Sinxronlash fayl yo'lini o'zgartirgan bo'lishi mumkin — bosilganda qayta aniqlaymiz.
                            (downloadedModelFile(context, model.id) ?: file)?.let {
                                if (hasEnoughRam(context, model)) onModelOpen(model, it)
                                else pendingOpen = model to it
                            }
                        },
                        onDeleteClick = { toDelete = model }
                    )
                }
            }
        }
    }

    pendingOpen?.let { (model, file) ->
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
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
                    pendingOpen = null
                    onModelOpen(model, file)
                }) { Text(tr("Baribir ochish"), color = DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { pendingOpen = null }) { Text(tr("Bekor qilish"), color = TextSecondary) }
            }
        )
    }

    pendingMetered?.let { model ->
        val remainingMb = (model.totalDownloadSizeMb - partialDownloadBytes(context, model.id) / (1024L * 1024L))
            .coerceAtLeast(0L)
        AlertDialog(
            onDismissRequest = { pendingMetered = null },
            containerColor = BgCardElevated,
            title = { Text(tr("Mobil internet"), color = TextPrimary, fontSize = 17.sp) },
            text = {
                Text(
                    tr("Hozir mobil internet ishlatilmoqda. %1\$s MB yuklab olinadi — Wi-Fi'ga ulanish tavsiya etiladi.", remainingMb),
                    color = TextSecondary, fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingMetered = null
                    onModelSelected(model)
                }) { Text(tr("Baribir yuklash"), color = DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMetered = null }) { Text(tr("Bekor qilish"), color = TextSecondary) }
            }
        )
    }

    toDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = BgCardElevated,
            title = { Text(tr("Modelni o'chirish"), color = TextPrimary, fontSize = 17.sp) },
            text = {
                Text(
                    tr("%1\$s telefondan o'chiriladi. Kerak bo'lsa, uni qaytadan yuklab olish mumkin.", model.name),
                    color = TextSecondary, fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = toDelete
                    toDelete = null
                    if (m != null) {
                        deleteDownloadedModel(context, m.id)
                        CustomModelStore.remove(context, m.id)
                        models = ModelCatalog.models
                        downloadedVersion++
                        Toast.makeText(context, tr("%1\$s o'chirildi", m.name), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(tr("O'chirish"), color = DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(tr("Bekor qilish"), color = TextSecondary) }
            }
        )
    }

    toDeletePartial?.let { model ->
        AlertDialog(
            onDismissRequest = { toDeletePartial = null },
            containerColor = BgCardElevated,
            title = { Text(tr("Yuklangan qismi o'chirilsinmi?"), color = TextPrimary, fontSize = 17.sp) },
            text = {
                Text(
                    tr("Modelning to'xtatilgan qismi o'chiriladi va xotiradan joy bo'shatiladi."),
                    color = TextSecondary, fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = toDeletePartial
                    toDeletePartial = null
                    m?.let {
                        deleteModelPartials(context, it.id)
                        downloadedVersion++
                        Toast.makeText(context, tr("Yuklangan qism o'chirildi"), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(tr("O'chirish"), color = DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { toDeletePartial = null }) { Text(tr("Bekor qilish"), color = TextSecondary) }
            }
        )
    }

    if (showAddCustomDialog) {
        AddCustomModelDialog(
            onDismiss = { showAddCustomDialog = false },
            onModelAddedAndStartDownload = { newModel ->
                models = ModelCatalog.models
                onModelSelected(newModel)
            },
            onModelImported = { importedModel ->
                models = ModelCatalog.models
                downloadedVersion++
            }
        )
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Sizing.radiusPill)
    Text(
        text,
        color = if (selected) AccentPurpleText else TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) AccentPurpleBg else BgCardAlt)
            .border(0.5.dp, if (selected) AccentPurple else BorderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@Composable
private fun TierHeader(tier: ModelTier) {
    val desc = when (tier) {
        ModelTier.FAST -> tr("Tezkor: har biri bitta vazifaga, hamma telefonda ishlaydi")
        ModelTier.AGENT -> tr("Agent: fayllar bilan ishlaydi (yaratish, o'zgartirish, o'chirish), ixcham rejim")
        ModelTier.POWER -> tr("Kuchli: to'liq agent va buyruqlar, ko'p RAM talab qiladi")
    }
    Column(Modifier.padding(top = 6.dp)) {
        Text(tier.label, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(desc, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun ModelCard(
    model: ModelItem,
    stars: Int,
    starred: Boolean,
    canStar: Boolean,
    onStarClick: () -> Unit,
    downloaded: Boolean,
    partialBytes: Long = 0L,
    tooHeavy: Boolean,
    recommendedGb: Int?,
    activeDownload: ActiveDownload?,
    onViewDownload: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelPartial: () -> Unit = {},
    onOpenClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Sizing.radiusLg))
            .background(BgCardElevated)
            .border(1.dp, if (downloaded) SuccessGreen.copy(alpha = 0.35f) else BorderColor, RoundedCornerShape(Sizing.radiusLg))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                model.badge?.let {
                    val (bBg, bFg) = if (it == ModelBadge.NEW) CodingBg to CodingText else AccentPurpleBg to AccentPurpleText
                    Badge(
                        text = it.label, bg = bBg, fg = bFg,
                        icon = if (it == ModelBadge.RECOMMENDED) Icons.Filled.Star else null
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(model.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(tr(model.direction), color = TextSecondary, fontSize = 11.sp)
                // Tezkor modellar: aniq yo'nalish belgisi
                model.role?.let {
                    Spacer(Modifier.height(6.dp))
                    Badge(text = it.label, bg = BgCardAlt, fg = TextSecondary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                val (categoryBg, categoryFg) = when (model.category) {
                    ModelCategory.CODING -> CodingBg to CodingText
                    ModelCategory.CHAT -> AccentPurpleBg to AccentPurpleText
                    ModelCategory.TRANSLATION -> SuccessGreenBg to SuccessGreenText
                    ModelCategory.REASONING -> ReasoningBg to ReasoningText
                }
                Badge(text = model.category.label, bg = categoryBg, fg = categoryFg)
                Spacer(Modifier.height(6.dp))
                // 1B-2B modellar buyruq bajara olmaydi (faqat suhbat) —
                // ModelItem.isAgentCapable'ga qarang. Foydalanuvchi buni
                // yuklab olishdan OLDIN bilishi kerak.
                val (modeBg, modeFg) = if (model.isAgentCapable) SuccessGreenBg to SuccessGreenText else BgCardAlt to TextSecondary
                Badge(text = model.modeLabel, bg = modeBg, fg = modeFg)
                if (downloaded) {
                    Spacer(Modifier.height(6.dp))
                    Badge(text = tr("Yuklab olingan"), bg = SuccessGreenBg, fg = SuccessGreenText, icon = Icons.Filled.CheckCircle)
                } else if (activeDownload != null || partialBytes > 0) {
                    Spacer(Modifier.height(6.dp))
                    val isActivelyRunning = activeDownload != null && !activeDownload.paused
                    Badge(
                        text = if (isActivelyRunning) tr("Yuklanmoqda") else tr("To'xtatilgan"),
                        bg = AccentPurpleBg,
                        fg = AccentPurpleText,
                        icon = if (isActivelyRunning) Icons.Filled.Download else Icons.Filled.Pause
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("⬇ ${model.baseSizeMb} MB", color = TextMuted, fontSize = 11.sp)
            Text(tr("⚙ ~%1\$s MB RAM", model.baseRamMb), color = TextMuted, fontSize = 11.sp)
            // Yulduzcha: hamma sonini ko'radi, bosish faqat canStar bo'lsa
            if (stars > 0 || canStar) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.then(if (canStar) Modifier.clickable(onClick = onStarClick) else Modifier)
                ) {
                    Icon(
                        if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = tr("Yulduzcha"),
                        tint = if (starred) CodingText else TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("$stars", color = TextMuted, fontSize = 11.sp)
                }
            }
        }
        Text(tr("Mos telefon: %1\$s", tr(model.minPhoneClass)), color = TextMuted, fontSize = 10.sp)
        if (tooHeavy) {
            Spacer(Modifier.height(6.dp))
            Badge(text = tr("Telefoningiz uchun og'ir"), bg = BgCardAlt, fg = DangerText)
        } else if (recommendedGb != null) {
            Text(
                tr("%1\$s GB+ RAM tavsiya etiladi", recommendedGb),
                color = TextMuted, fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Kontekst uzunligi endi Sozlamalar -> Kontekst'dan o'zgartiriladi.
        Text(
            tr("Kontekst: %1\$sK (Sozlamalardan o'zgartiriladi)", model.contextSize / 1024),
            color = TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(12.dp))
        if (downloaded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = tr("Ochish"),
                    icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenClick
                )
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(Sizing.radiusMd))
                        .background(BgCardAlt)
                        .border(1.dp, BorderColor, RoundedCornerShape(Sizing.radiusMd))
                        .clickable(onClick = onDeleteClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = tr("O'chirish"), tint = DangerText, modifier = Modifier.size(18.dp))
                }
            }
        } else if (activeDownload != null || partialBytes > 0) {
            val isActivelyRunning = activeDownload != null && !activeDownload.paused
            val doneBytes = if (activeDownload != null && activeDownload.bytesDone > 0) activeDownload.bytesDone else partialBytes
            val totalBytes = (model.totalDownloadSizeMb * 1024L * 1024L).coerceAtLeast(1L)
            val percent = if (activeDownload != null) activeDownload.percent else ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 99)
            val doneMb = doneBytes / (1024 * 1024)
            val totalMb = model.totalDownloadSizeMb

            LinearProgressIndicator(
                progress = percent / 100f,
                color = AccentPurple,
                trackColor = BgCard,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isActivelyRunning) tr("Yuklanmoqda: %1\$s%%", percent)
                    else tr("Pauzada: %1\$s%%", percent),
                    color = if (isActivelyRunning) AccentPurpleText else TextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "$doneMb / $totalMb MB",
                    color = TextMuted,
                    fontSize = 11.5.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            if (isActivelyRunning) {
                PrimaryButton(
                    text = tr("Yuklanishni ko'rish"),
                    icon = Icons.Filled.Download,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onViewDownload
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val remainingMb = (totalMb - doneMb).coerceAtLeast(0)
                    PrimaryButton(
                        text = tr("Davom etish (%1\$s MB qoldi)", remainingMb),
                        icon = Icons.Filled.PlayArrow,
                        modifier = Modifier.weight(1f),
                        onClick = onDownloadClick
                    )
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(Sizing.radiusMd))
                            .background(BgCardAlt)
                            .border(1.dp, BorderColor, RoundedCornerShape(Sizing.radiusMd))
                            .clickable(onClick = onCancelPartial),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = tr("Bekor qilish"),
                            tint = DangerText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        } else {
            PrimaryButton(
                text = tr("Yuklab olish (%1\$s MB)", model.totalDownloadSizeMb),
                icon = Icons.Filled.Download,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDownloadClick
            )
        }
    }
}

@Composable
private fun Badge(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(Sizing.radiusPill)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Sizing.radiusMd))
            .background(AccentGradient)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

internal fun totalRamGb(context: Context): Double {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return info.totalMem / (1024.0 * 1024 * 1024)
}

/** Telefonning umumiy RAM'i modelni ko'tara oladimi (RAM/2 qoidasi, kontekst hisobga olingan). */
internal fun fitsTotalRam(totalGb: Double, model: ModelItem): Boolean {
    val ctxFactor = 1.0 + 0.4 * (model.contextSize / STANDARD_CTX.toDouble() - 1.0)
    return model.paramsBillion * ctxFactor.coerceAtLeast(1.0) <= totalGb / 2.0
}

/** 3B+ modellar uchun tavsiya: kamida 8 GB, katta modellarga RAM/2 qoidasiga mos ravishda ko'proq. */
private fun recommendedRamGb(model: ModelItem): Int =
    maxOf(8, ceil(model.paramsBillion * 2.0).toInt())

internal fun hasEnoughRam(context: Context, model: ModelItem): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    val availableMb = info.availMem / (1024 * 1024)
    val totalGb = info.totalMem / (1024.0 * 1024 * 1024)

    // MUHIM TUZATISH (foydalanuvchi so'rovi bo'yicha): ilgari bu yerda
    // "totalMem'ning 60%'i" kabi xiralashgan foizga asoslangan hisob bor
    // edi — modelning RAM talabini taxminiy baseRamMb bilan solishtirib.
    // Endi qoida ANIQ va OLDINDAN AYTIB BO'LADIGAN: telefonning umumiy
    // RAM (GB) hajmi qancha bo'lsa, shundan 2 baravar KICHIK (mlrd
    // parametrda) modelgacha ruxsat beriladi — ya'ni:
    //   8 GB RAM  -> 4B modelgacha
    //   6 GB RAM  -> 3B modelgacha
    //   4 GB RAM  -> 2B modelgacha
    //   va hokazo (paramsBillion <= totalGb / 2)
    // Bu Q4 kvantlash uchun xavfsiz zaxira: og'irliklar + KV-kesh +
    // ishga tushirish xarajati odatda ~0.5-0.6 GB/mlrd parametrni
    // tashkil qiladi — RAM/2 qoidasi shuni bemalol qamrab oladi va
    // tizim/fon ilovalariga ham joy qoldiradi.
    val maxParamsForThisRam = totalGb / 2.0
    // Kengaytirilgan kontekst tanlangan bo'lsa, model sezilarli ko'proq
    // RAM yeydi (ModelItem.estimatedRamRequiredMb'da ham +40% hisoblanadi)
    // — shunga mos "qattiqroq" talab qo'yamiz.
    val ctxFactor = 1.0 + 0.4 * (model.contextSize / STANDARD_CTX.toDouble() - 1.0)
    val requiredParams = model.paramsBillion * ctxFactor.coerceAtLeast(1.0)
    val passesRatio = requiredParams <= maxParamsForThisRam

    // Qo'shimcha, ikkinchi darajali xavfsizlik: nisbat mos kelsa ham, agar
    // AYNAN HOZIR juda oz bo'sh RAM qolgan bo'lsa (masalan o'nlab og'ir
    // ilova fonda ochiq), baribir ruxsat bermaymiz.
    val hasMinimalHeadroom = availableMb >= 600
    return passesRatio && hasMinimalHeadroom
}

/** Do'kondagi kartada ko'rsatiladigan faol (ketayotgan yoki pauzadagi) yuklash. */
private data class ActiveDownload(
    val modelId: String,
    val percent: Int,
    val paused: Boolean,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L
)

private fun ModelDownloadState.toActive(): ActiveDownload? = when (this) {
    is ModelDownloadState.Running -> ActiveDownload(modelId, progress.percent, false, progress.bytesDone, progress.bytesTotal)
    is ModelDownloadState.Paused -> ActiveDownload(modelId, progress.percent, true, progress.bytesDone, progress.bytesTotal)
    else -> null
}
