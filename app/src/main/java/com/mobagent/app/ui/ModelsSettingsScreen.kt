package com.mobagent.app.ui

import com.mobagent.app.i18n.mirrorInRtl
import com.mobagent.app.i18n.tr
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.model.CustomModelStore
import com.mobagent.app.model.ModelCatalog
import com.mobagent.app.model.ModelItem
import com.mobagent.app.model.deleteDownloadedModel
import com.mobagent.app.model.downloadedModelFile
import com.mobagent.app.ui.theme.*
import java.util.Locale

private fun formatSize(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb)
    else "${(bytes / 1_048_576.0).toLong()} MB"
}

/** Sozlamalar -> Modellar: yuklab olingan oflayn modellar, hajmi va o'chirish. */
@Composable
fun ModelsSettingsScreen(onBack: () -> Unit, onOpenStore: () -> Unit) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(Sizing.radiusMd)
    var version by remember { mutableIntStateOf(0) }
    var toDelete by remember { mutableStateOf<ModelItem?>(null) }

    LaunchedEffect(Unit) {
        ModelCatalog.custom = CustomModelStore.load(context)
        version++
    }

    val items: List<Pair<ModelItem, Long>> = remember(version) {
        ModelCatalog.models.mapNotNull { m -> downloadedModelFile(context, m.id)?.let { m to it.length() } }
    }
    val totalBytes = items.sumOf { it.second }
    val freeBytes = remember(version) { context.filesDir.usableSpace }

    Column(
        Modifier.fillMaxSize().background(BgApp).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ArrowBackIosNew, contentDescription = tr("Orqaga"), tint = TextSecondary,
                modifier = Modifier.size(16.dp).clickable(onClick = onBack).mirrorInRtl()
            )
            Spacer(Modifier.width(14.dp))
            Text(tr("Modellar"), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }

        // Xotira
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().clip(cardShape).background(BgCardElevated).border(1.dp, BorderColor, cardShape).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(tr("Modellar egallagan joy"), color = TextMuted, fontSize = 12.sp)
                Text(formatSize(totalBytes), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(tr("Bo'sh joy"), color = TextMuted, fontSize = 12.sp)
                Text(formatSize(freeBytes), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Ro'yxat
        Spacer(Modifier.height(16.dp))
        if (items.isEmpty()) {
            Text(
                tr("Hali hech qanday model yuklab olinmagan."),
                color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { (model, bytes) ->
                    Row(
                        Modifier.fillMaxWidth().clip(cardShape).background(BgCardElevated)
                            .border(1.dp, BorderColor, cardShape).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                model.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                tr("%1\$s · %2\$s rejimi · %3\$s", formatSize(bytes), model.modeLabel, model.category.label),
                                color = TextMuted, fontSize = 12.sp
                            )
                        }
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(Sizing.radiusMd)).clickable { toDelete = model },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = tr("O'chirish"), tint = DangerText, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // Yangi model
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(Sizing.radiusMd)).background(BrandPurple)
                .clickable(onClick = onOpenStore),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Yangi model yuklab olish"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))
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
                        version++
                        Toast.makeText(context, tr("%1\$s o'chirildi", m.name), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(tr("O'chirish"), color = DangerText) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(tr("Bekor qilish"), color = TextSecondary) } }
        )
    }
}
