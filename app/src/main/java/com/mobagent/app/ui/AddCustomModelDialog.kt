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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mobagent.app.i18n.tr
import com.mobagent.app.model.*
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AddCustomModelDialog(
    onDismiss: () -> Unit,
    onModelAddedAndStartDownload: (ModelItem) -> Unit,
    onModelImported: (ModelItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) } // 0: Hugging Face, 1: Telefondan fayl
    var hfInput by remember { mutableStateOf("") }
    var modelNameInput by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var foundFiles by remember { mutableStateOf<List<HuggingFaceGgufFile>>(emptyList()) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isLoading = true
            statusText = tr("Fayl ilovaga nusxalanmoqda…")
            errorMessage = null
            scope.launch {
                val res = importLocalGgufFile(context, uri, modelNameInput)
                isLoading = false
                statusText = null
                res.onSuccess { model ->
                    onModelImported(model)
                    onDismiss()
                }.onFailure { err ->
                    errorMessage = err.localizedMessage ?: tr("Faylni yuklashda xatolik yuz berdi")
                }
            }
        }
    }

    fun submitHf() {
        val raw = hfInput.trim()
        if (raw.isBlank()) {
            errorMessage = tr("Hugging Face repo yoki linkini kiriting")
            return
        }
        errorMessage = null
        val normalized = normalizeGgufUrl(raw)

        // 1. Agar to'g'ridan-to'g'ri .gguf linki bo'lsa
        if (normalized.endsWith(".gguf", ignoreCase = true) && normalized.startsWith("http", ignoreCase = true)) {
            val fileName = normalized.substringAfterLast("/").removeSuffix(".gguf")
            val displayName = modelNameInput.ifBlank { fileName }
            val family = inferModelFamily(displayName)
            val params = inferParamsBillion(displayName)
            val safeId = "custom_" + System.currentTimeMillis()

            val customModel = ModelItem(
                id = safeId,
                name = displayName,
                downloadUrl = normalized,
                direction = "Hugging Face orqali qo'shilgan model",
                category = ModelCategory.CHAT,
                baseSizeMb = 1500, // yuklab olish jarayonida haqiqiy hajm aniqlanadi
                baseRamMb = 2000,
                minPhoneClass = "Maxsus model",
                family = family,
                paramsBillion = params
            )
            CustomModelStore.add(context, customModel)
            onModelAddedAndStartDownload(customModel)
            onDismiss()
            return
        }

        // 2. Agar repo bo'lsa (TheBloke/Model-GGUF yoki URL)
        isLoading = true
        statusText = tr("Hugging Face'dan GGUF fayllar qidirilmoqda…")
        foundFiles = emptyList()

        scope.launch {
            val res = fetchHuggingFaceGgufFiles(raw)
            isLoading = false
            statusText = null
            res.onSuccess { files ->
                foundFiles = files
            }.onFailure { err ->
                errorMessage = err.localizedMessage ?: tr("Hugging Face omborini tekshirishda xatolik")
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BgCardElevated,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tr("Oflayn model qo'shish"),
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = tr("Yopish"),
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Tabs (Hugging Face vs Telefondan)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Sizing.radiusPill))
                        .background(BgApp)
                        .padding(3.dp)
                ) {
                    val tabShape = RoundedCornerShape(Sizing.radiusPill)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(tabShape)
                            .background(if (selectedTab == 0) AccentPurple else BgApp)
                            .clickable { selectedTab = 0; errorMessage = null }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Hugging Face",
                            color = if (selectedTab == 0) TextPrimary else TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(tabShape)
                            .background(if (selectedTab == 1) AccentPurple else BgApp)
                            .clickable { selectedTab = 1; errorMessage = null }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            tr("Fayldan (.gguf)"),
                            color = if (selectedTab == 1) TextPrimary else TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (selectedTab == 0) {
                    // TAB 0: Hugging Face
                    Text(
                        tr("Repo nomi yoki to'g'ridan-to'g'ri .gguf linki:"),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = hfInput,
                        onValueChange = { hfInput = it; errorMessage = null },
                        placeholder = { Text("TheBloke/Llama-2-7B-Chat-GGUF", color = TextMuted, fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(Sizing.radiusMd),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentPurple,
                            unfocusedBorderColor = BorderColor,
                            focusedContainerColor = BgApp,
                            unfocusedContainerColor = BgApp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))
                    Text(
                        tr("Model nomi (ixtiyoriy):"),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = modelNameInput,
                        onValueChange = { modelNameInput = it },
                        placeholder = { Text("Mening modelim", color = TextMuted, fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(Sizing.radiusMd),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentPurple,
                            unfocusedBorderColor = BorderColor,
                            focusedContainerColor = BgApp,
                            unfocusedContainerColor = BgApp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))

                    Button(
                        onClick = { submitHf() },
                        enabled = !isLoading && hfInput.isNotBlank(),
                        shape = RoundedCornerShape(Sizing.radiusPill),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Qidirish / Qo'shish"), color = TextPrimary, fontSize = 14.sp)
                    }

                    if (foundFiles.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            tr("Topilgan GGUF modellar (%1\$s ta). Birini tanlang:", foundFiles.size),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(foundFiles) { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BgApp)
                                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                                        .clickable {
                                            val displayName = modelNameInput.ifBlank {
                                                file.filename.substringBeforeLast(".gguf")
                                            }
                                            val family = inferModelFamily(displayName)
                                            val params = inferParamsBillion(displayName)
                                            val safeId = "custom_" + System.currentTimeMillis()

                                            val customModel = ModelItem(
                                                id = safeId,
                                                name = displayName,
                                                downloadUrl = file.downloadUrl,
                                                direction = "Hugging Face: ${file.filename}",
                                                category = ModelCategory.CHAT,
                                                baseSizeMb = file.sizeMb,
                                                baseRamMb = (file.sizeMb * 1.3).toLong().coerceAtLeast(800),
                                                minPhoneClass = "Maxsus model",
                                                family = family,
                                                paramsBillion = params
                                            )
                                            CustomModelStore.add(context, customModel)
                                            onModelAddedAndStartDownload(customModel)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            file.filename,
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text("${file.sizeMb} MB", color = TextMuted, fontSize = 11.5.sp)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                } else {
                    // TAB 1: Telefondan (.gguf) fayl tanlash
                    Text(
                        tr("Telefonga avval yuklab olingan .gguf model faylini tanlang va oflayn chatda darhol ishlating:"),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    Text(
                        tr("Model nomi (ixtiyoriy):"),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = modelNameInput,
                        onValueChange = { modelNameInput = it },
                        placeholder = { Text("Model nomi", color = TextMuted, fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(Sizing.radiusMd),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentPurple,
                            unfocusedBorderColor = BorderColor,
                            focusedContainerColor = BgApp,
                            unfocusedContainerColor = BgApp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(Sizing.radiusPill),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr(".gguf faylni tanlash"), color = TextPrimary, fontSize = 14.sp)
                    }
                }

                if (isLoading) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = AccentPurple, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        statusText?.let {
                            Spacer(Modifier.width(10.dp))
                            Text(it, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                errorMessage?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = DangerText, fontSize = 12.sp)
                }
            }
        }
    }
}
