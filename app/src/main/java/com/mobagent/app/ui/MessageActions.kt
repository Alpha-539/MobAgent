package com.mobagent.app.ui

import com.mobagent.app.i18n.tr
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mobagent.app.ui.theme.SuccessGreen
import com.mobagent.app.ui.theme.TextMuted
import kotlinx.coroutines.delay

/**
 * AI javobi tagidagi kichik tugmalar qatori: nusxalash, ulashish va
 * (faqat oxirgi javobda) qayta yaratish.
 */
@Composable
fun MessageActions(
    text: String,
    modifier: Modifier = Modifier,
    onRegenerate: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    // Google Play (AI-Generated Content): ilova ichidan shikoyat qilish (ReportDialog).
    var reporting by remember(text) { mutableStateOf(false) }

    // "Nusxalandi" belgisi (galochka) 1.6 soniyadan keyin oddiy ikonkaga qaytadi.
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ActionIcon(
            icon = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
            description = if (copied) tr("Nusxalandi") else tr("Nusxalash"),
            tint = if (copied) SuccessGreen else TextMuted
        ) {
            clipboard.setText(AnnotatedString(text))
            copied = true
        }
        ActionIcon(Icons.Outlined.Share, tr("Ulashish")) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, tr("Ulashish")))
        }
        if (onRegenerate != null) {
            ActionIcon(Icons.Outlined.Refresh, tr("Qayta yaratish"), onClick = onRegenerate)
        }
        ActionIcon(Icons.Outlined.Flag, tr("Shikoyat qilish")) { reporting = true }
    }
    if (reporting) {
        ReportDialog(aiReply = text, onDismiss = { reporting = false })
    }
}

/**
 * Foydalanuvchi xabari tagidagi tugmalar: nusxalash va (faqat oxirgi
 * xabarda) tahrirlash.
 */
@Composable
fun UserMessageActions(
    text: String,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ActionIcon(
            icon = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
            description = if (copied) tr("Nusxalandi") else tr("Nusxalash"),
            tint = if (copied) SuccessGreen else TextMuted
        ) {
            clipboard.setText(AnnotatedString(text))
            copied = true
        }
        if (onEdit != null) {
            ActionIcon(Icons.Outlined.Edit, tr("Tahrirlash"), onClick = onEdit)
        }
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    description: String,
    tint: Color = TextMuted,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(17.dp))
    }
}

/** AI javobi tagidagi kichik ogohlantirish yozuvi. */
@Composable
fun AiDisclaimer(modifier: Modifier = Modifier) {
    Text(
        tr("MobAgent sun'iy intellekt va xato qilishi mumkin"),
        color = TextMuted,
        fontSize = 10.sp,
        modifier = modifier
    )
}
