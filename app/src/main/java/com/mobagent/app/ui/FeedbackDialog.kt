package com.mobagent.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.i18n.AppLanguage
import com.mobagent.app.i18n.tr
import com.mobagent.app.model.ApiClient
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.launch

private const val MAX_MESSAGE = 1000
private const val MIN_MESSAGE = 5
private const val MAX_CONTACT = 120

/** Sozlamalar → "Fikr bildirish": taklif / xato / boshqa. Admin panelning "Fikrlar" bo'limiga boradi. */
@Composable
fun FeedbackDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("idea") }
    var message by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldShape = RoundedCornerShape(Sizing.radiusSm)
    val canSend = !sending && message.trim().length >= MIN_MESSAGE

    fun send() {
        sending = true
        error = null
        scope.launch {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            val res = ApiClient.sendFeedback(
                kind, message.trim(), contact.trim(), version, AppLanguage.effective.code
            )
            sending = false
            when (res) {
                ApiClient.FeedbackResult.Ok -> {
                    Toast.makeText(context, tr("Rahmat! Fikringiz yuborildi"), Toast.LENGTH_LONG).show()
                    onDismiss()
                }
                ApiClient.FeedbackResult.RateLimited -> error = tr("Juda ko'p fikr yuborildi, keyinroq urinib ko'ring")
                ApiClient.FeedbackResult.NeedLogin -> error = tr("Fikr yuborish uchun hisobga kiring va emailni tasdiqlang")
                ApiClient.FeedbackResult.Failed -> error = tr("Yuborib bo'lmadi. Internetni tekshirib, qayta urinib ko'ring")
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = BgCardElevated,
        title = { Text(tr("Fikr bildirish"), color = TextPrimary, fontSize = 17.sp) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("idea" to tr("Taklif"), "bug" to tr("Xato"), "other" to tr("Boshqa")).forEach { (k, label) ->
                        val on = kind == k
                        Text(
                            label,
                            color = if (on) AccentPurpleText else TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (on) AccentPurpleBg else BgApp)
                                .border(1.dp, if (on) AccentPurple else BorderColor, RoundedCornerShape(999.dp))
                                .clickable(enabled = !sending) { kind = k }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 110.dp).clip(fieldShape).background(BgApp)
                        .border(1.dp, BorderColor, fieldShape).padding(12.dp)
                ) {
                    if (message.isEmpty()) {
                        Text(tr("Fikr yoki taklifingizni yozing…"), color = TextMuted, fontSize = 14.sp)
                    }
                    BasicTextField(
                        value = message,
                        onValueChange = { if (it.length <= MAX_MESSAGE) message = it; error = null },
                        enabled = !sending,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(AccentPurple),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    "${message.length} / $MAX_MESSAGE", color = TextMuted, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().clip(fieldShape).background(BgApp)
                        .border(1.dp, BorderColor, fieldShape).padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (contact.isEmpty()) {
                        Text(tr("Aloqa (ixtiyoriy): Telegram yoki email"), color = TextMuted, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = contact,
                        onValueChange = { if (it.length <= MAX_CONTACT) contact = it },
                        enabled = !sending,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                        cursorBrush = SolidColor(AccentPurple),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = DangerText, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { send() }, enabled = canSend) {
                Text(
                    if (sending) tr("Yuborilmoqda…") else tr("Yuborish"),
                    color = if (canSend) AccentPurpleText else TextMuted
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text(tr("Bekor qilish"), color = TextSecondary) }
        }
    )
}
