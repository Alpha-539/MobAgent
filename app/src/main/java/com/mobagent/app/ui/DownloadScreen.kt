package com.mobagent.app.ui

import com.mobagent.app.i18n.tr
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import com.mobagent.app.model.DownloadProgress
import com.mobagent.app.model.ModelDownloadService
import com.mobagent.app.model.ModelDownloadState
import com.mobagent.app.model.ModelItem
import com.mobagent.app.ui.theme.*
import java.io.File

/**
 * Model yuklab olinayotganda ko'rsatiladigan kutish ekrani.
 * Haqiqiy yuklab olish endi `ModelDownloadService` (Foreground Service)da
 * ishlaydi — bu ekran shunchaki uning holatini kuzatib, ko'rsatib turadi.
 * Foydalanuvchi orqaga qaytsa, ilovani fonga tashlasa yoki hatto ekrandan
 * o'chirsa ham (tizim jarayonni o'ldirmaguncha) yuklab olish davom etadi —
 * shu sababli "Ilovani yopmang" degan eski ogohlantirish endi kerak emas.
 */
@Composable
fun DownloadScreen(
    model: ModelItem,
    onFinished: (modelFile: File) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit = {}
) {
    val context = LocalContext.current
    val state by ModelDownloadService.state.collectAsState()
    var confirmCancel by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* rad etilsa ham yuklab olish davom etadi, faqat bildirishnoma ko'rinmaydi */ }

    LaunchedEffect(model.id) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Agar shu model uchun yuklab olish allaqachon fonda ketayotgan bo'lsa
        // (masalan foydalanuvchi ekrandan chiqib qaytgan bo'lsa), qayta
        // boshlanmaydi — mavjud holat shunchaki kuzatiladi.
        val current = ModelDownloadService.state.value
        val alreadyActiveSameModel =
            (current is ModelDownloadState.Running && current.modelId == model.id) ||
            (current is ModelDownloadState.Paused && current.modelId == model.id)
        if (!alreadyActiveSameModel) {
            ModelDownloadService.start(context, model)
        }
    }

    LaunchedEffect(state) {
        when (val s = state) {
            is ModelDownloadState.Success -> if (s.modelId == model.id) {
                ModelDownloadService.consume()
                onFinished(s.file)
            }
            is ModelDownloadState.Error -> if (s.modelId == model.id) {
                ModelDownloadService.consume()
                onError(s.message)
            }
            else -> {}
        }
    }

    val running = state as? ModelDownloadState.Running
    val paused = (state as? ModelDownloadState.Paused)?.takeIf { it.modelId == model.id }
    val isPaused = paused != null
    val progress = running?.progress
        ?: paused?.progress
        ?: DownloadProgress(tr("Boshlanmoqda"), 0, 0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LogoMark(fontSize = 40.sp)
        Spacer(Modifier.height(16.dp))
        if (isPaused) {
            Icon(Icons.Filled.Pause, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(48.dp))
        } else {
            CircularProgressIndicator(color = AccentPurple, strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(model.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (isPaused) tr("Pauzada") else tr("%1\$s yuklanmoqda...", progress.stage),
            color = TextSecondary,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(BgCard)
        ) {
            LinearProgressIndicator(
                progress = progress.percent / 100f,
                color = AccentPurple,
                trackColor = BgCard,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${progress.percent}%", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (progress.bytesTotal > 0) {
                Text(
                    "${mbOf(progress.bytesDone)} / ${mbOf(progress.bytesTotal)} MB",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        PauseResumeButton(
            paused = isPaused,
            onClick = {
                if (isPaused) ModelDownloadService.start(context, model) // .part fayldan davom etadi
                else ModelDownloadService.pause()
            }
        )

        Spacer(Modifier.height(12.dp))
        Text(
            tr("Bekor qilish"),
            color = DangerText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(Sizing.radiusPill))
                .clickable { confirmCancel = true }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )

        Spacer(Modifier.height(12.dp))
        Text(
            if (isPaused) tr("Pauzada — yuklangan qismi saqlangan, davom ettirsangiz shu joydan boshlanadi")
            else tr("Fon rejimida yuklanmoqda — ilovadan chiqsangiz ham davom etadi"),
            color = TextMuted,
            fontSize = 11.sp
        )
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            containerColor = BgCardElevated,
            title = { Text(tr("Yuklab olish bekor qilinsinmi?"), color = TextPrimary, fontSize = 17.sp) },
            text = { Text(tr("Yuklangan qismi o'chiriladi."), color = TextSecondary, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    ModelDownloadService.cancel(context, model.id)
                    onCancel()
                }) { Text(tr("Bekor qilish"), color = DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(tr("Davom ettirish"), color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun PauseResumeButton(paused: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(Sizing.radiusPill))
            .background(if (paused) TextPrimary else BgCardElevated)
            .then(if (paused) Modifier else Modifier.border(1.dp, BorderColorStrong, RoundedCornerShape(Sizing.radiusPill)))
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (paused) BgApp else TextPrimary
        Icon(
            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (paused) tr("Davom ettirish") else tr("Pauza"),
            color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
    }
}

private fun mbOf(bytes: Long): Long = bytes / (1024 * 1024)
