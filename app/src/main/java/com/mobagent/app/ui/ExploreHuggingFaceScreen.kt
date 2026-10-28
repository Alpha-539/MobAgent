package com.mobagent.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mobagent.app.i18n.mirrorInRtl
import com.mobagent.app.i18n.tr
import com.mobagent.app.model.*
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private fun formatCount(num: Long): String {
    return when {
        num >= 1_000_000 -> String.format(Locale.US, "%.1fM", num / 1_000_000.0)
        num >= 1_000 -> String.format(Locale.US, "%.1fK", num / 1_000.0)
        else -> num.toString()
    }
}

@Composable
fun ExploreHuggingFaceScreen(
    onBack: () -> Unit,
    onModelDownload: (ModelItem) -> Unit,
    onModelImported: (ModelItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<HuggingFaceModelSummary>>(emptyList()) }
    var popularResults by remember { mutableStateOf<List<HuggingFaceModelSummary>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // Quants dialog
    var selectedRepoForQuants by remember { mutableStateOf<HuggingFaceModelSummary?>(null) }
    var isFetchingQuants by remember { mutableStateOf(false) }
    var quantsList by remember { mutableStateOf<List<HuggingFaceGgufFile>>(emptyList()) }
    var quantsError by remember { mutableStateOf<String?>(null) }

    // Import from phone
    var isImporting by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isImporting = true
            importStatus = tr("Fayl ilovaga nusxalanmoqda…")
            scope.launch {
                val res = importLocalGgufFile(context, uri, "")
                isImporting = false
                importStatus = null
                res.onSuccess { model ->
                    onModelImported(model)
                    onBack()
                }.onFailure { err ->
                    searchError = err.localizedMessage ?: tr("Faylni yuklashda xatolik yuz berdi")
                }
            }
        }
    }

    // Dastlab ommabop modellarni fon orqali yuklash
    LaunchedEffect(Unit) {
        val res = searchHuggingFaceModels("")
        res.onSuccess { popularResults = it }
    }

    // Debounced qidiruv
    var searchJob by remember { mutableStateOf<Job?>(null) }
    fun onQueryChange(newQuery: String) {
        searchQuery = newQuery
        searchJob?.cancel()
        if (newQuery.isBlank()) {
            searchResults = emptyList()
            isSearching = false
            searchError = null
            return
        }
        searchJob = scope.launch {
            delay(500)
            isSearching = true
            searchError = null
            val res = searchHuggingFaceModels(newQuery)
            isSearching = false
            res.onSuccess {
                searchResults = it
            }.onFailure {
                searchError = it.localizedMessage ?: tr("Qidiruvda xatolik yuz berdi")
            }
        }
    }

    fun openQuantSelector(modelSummary: HuggingFaceModelSummary) {
        selectedRepoForQuants = modelSummary
        isFetchingQuants = true
        quantsList = emptyList()
        quantsError = null

        scope.launch {
            val res = fetchHuggingFaceGgufFiles(modelSummary.id)
            isFetchingQuants = false
            res.onSuccess { files ->
                quantsList = files
            }.onFailure { err ->
                quantsError = err.localizedMessage ?: tr("GGUF fayllarni yuklab bo'lmadi")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // Top Navigation Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(Sizing.radiusPill))
                    .clickable(onClick = onBack)
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            ) {
                Icon(
                    Icons.Filled.ArrowBackIosNew,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(15.dp).mirrorInRtl()
                )
                Spacer(Modifier.width(6.dp))
                Text(tr("Orqaga"), color = TextSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                tr("Hugging Face Modellari"),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(14.dp))

        // Search Input (Ixcham balandlikda)
        BasicTextField(
            value = searchQuery,
            onValueChange = { onQueryChange(it) },
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 13.5.sp),
            cursorBrush = SolidColor(AccentPurple),
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(Sizing.radiusPill))
                .background(BgCardElevated)
                .border(1.dp, BorderColor, RoundedCornerShape(Sizing.radiusPill))
                .padding(horizontal = 14.dp),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = AccentPurple,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                tr("Model nomi (qwen, llama, deepseek, coder)…"),
                                color = TextMuted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                    if (searchQuery.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = tr("Tozalash"),
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(17.dp)
                                .clickable { onQueryChange("") }
                        )
                    }
                }
            }
        )

        Spacer(Modifier.height(12.dp))

        // Telefondan fayl qo'shish kartasi
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Sizing.radiusMd))
                .background(BgCardElevated)
                .border(1.dp, BorderColor, RoundedCornerShape(Sizing.radiusMd))
                .clickable(enabled = !isImporting) { filePicker.launch(arrayOf("*/*")) }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AccentPurpleBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Telefondan .gguf faylni tanlash"),
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    tr("Yuklab olingan faylni darhol oflayn chatga qo'shadi"),
                    color = TextSecondary,
                    fontSize = 11.5.sp
                )
            }
        }

        if (isImporting) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = AccentPurple, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(importStatus.orEmpty(), color = TextSecondary, fontSize = 12.sp)
            }
        }

        searchError?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = DangerText, fontSize = 12.sp)
        }

        Spacer(Modifier.height(14.dp))

        // Ro'yxat
        if (isSearching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentPurple, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(tr("Hugging Face'dan modellar qidirilmoqda…"), color = TextSecondary, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (searchQuery.isNotBlank()) {
                    // Qidiruv natijalari
                    item {
                        Text(
                            tr("Qidiruv natijalari: \"%1\$s\" (%2\$s ta)", searchQuery, searchResults.size),
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    if (searchResults.isEmpty()) {
                        item {
                            Text(
                                tr("Mos GGUF model topilmadi. Boshqa so'z bilan qidirib ko'ring (masalan: qwen, llama, mistral)."),
                                color = TextMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    } else {
                        items(searchResults, key = { it.id }) { model ->
                            HuggingFaceModelCard(model = model, onDownloadClick = { openQuantSelector(model) })
                        }
                    }
                } else {
                    // Modellar ro'yxati sarlavhasi
                    item {
                        Text(
                            tr("Modellar"),
                            color = TextPrimary,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(TOP_10_CURATED_MODELS, key = { "top10-${it.id}" }) { model ->
                        HuggingFaceModelCard(model = model, onDownloadClick = { openQuantSelector(model) })
                    }

                    if (popularResults.isNotEmpty()) {
                        items(popularResults, key = { "popular-${it.id}" }) { model ->
                            HuggingFaceModelCard(model = model, onDownloadClick = { openQuantSelector(model) })
                        }
                    }
                }
            }
        }
    }

    // Quant (Q4_K_M, Q5_K_M va h.k.) tanlash oynasi
    selectedRepoForQuants?.let { targetModel ->
        Dialog(onDismissRequest = { selectedRepoForQuants = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = BgCardElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                targetModel.name,
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                targetModel.author,
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = tr("Yopish"),
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .clickable { selectedRepoForQuants = null }
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        tr("O'zingizga mos kvantlash (quant) variantini tanlang:"),
                        color = TextSecondary,
                        fontSize = 12.5.sp
                    )
                    Spacer(Modifier.height(10.dp))

                    if (isFetchingQuants) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AccentPurple, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.height(8.dp))
                                Text(tr("Fayllar ro'yxati olinmoqda…"), color = TextMuted, fontSize = 12.sp)
                            }
                        }
                    } else if (quantsError != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                quantsError.orEmpty(),
                                color = DangerText,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { openQuantSelector(targetModel) },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(tr("Qayta urinish"), color = Color.White, fontSize = 12.sp)
                            }
                        }
                    } else if (quantsList.isEmpty()) {
                        Text(tr("Bu omborda .gguf fayllar topilmadi."), color = TextMuted, fontSize = 12.sp)
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(quantsList) { file ->
                                val isRecommended = file.filename.contains("q4_k_m", ignoreCase = true) ||
                                        file.filename.contains("q4_0", ignoreCase = true)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isRecommended) AccentPurpleBg else BgApp)
                                        .border(
                                            1.dp,
                                            if (isRecommended) AccentPurple else BorderColor,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            val displayName = file.filename.removeSuffix(".gguf")
                                            val family = inferModelFamily(displayName)
                                            val params = inferParamsBillion(displayName)
                                            val safeId = "custom_" + System.currentTimeMillis()

                                            val customModel = ModelItem(
                                                id = safeId,
                                                name = displayName,
                                                downloadUrl = file.downloadUrl,
                                                direction = targetModel.description,
                                                category = targetModel.category,
                                                baseSizeMb = file.sizeMb,
                                                baseRamMb = (file.sizeMb * 1.3).toLong().coerceAtLeast(800),
                                                minPhoneClass = "Maxsus model",
                                                family = family,
                                                paramsBillion = params
                                            )
                                            CustomModelStore.add(context, customModel)
                                            selectedRepoForQuants = null
                                            onModelDownload(customModel)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                file.filename,
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = if (isRecommended) FontWeight.SemiBold else FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isRecommended) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    tr("Tavsiya"),
                                                    color = AccentPurpleText,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier
                                                        .background(AccentPurple.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text("${file.sizeMb} MB", color = TextMuted, fontSize = 11.5.sp)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Icon(
                                        Icons.Filled.CloudDownload,
                                        contentDescription = null,
                                        tint = AccentPurple,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HuggingFaceModelCard(
    model: HuggingFaceModelSummary,
    onDownloadClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(Sizing.radiusMd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(BgCardElevated)
            .border(1.dp, BorderColor, cardShape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.name,
                    color = TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                model.author,
                color = TextSecondary,
                fontSize = 11.5.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                model.description,
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Kategoriya
                Text(
                    model.category.label,
                    color = AccentPurpleText,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(AccentPurpleBg, RoundedCornerShape(Sizing.radiusPill))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
                // Yuklab olishlar soni
                Text(
                    "⬇ ${formatCount(model.downloads)}",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                val stars = ModelStarStore.getStars(LocalContext.current, model.id)
                Text(
                    "⭐ $stars",
                    color = Color(0xFFF59E0B),
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Yuklab olish tugmasi
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Sizing.radiusPill))
                .background(AccentPurple)
                .clickable(onClick = onDownloadClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    tr("Yuklash"),
                    color = TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
