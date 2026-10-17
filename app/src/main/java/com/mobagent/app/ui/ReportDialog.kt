package com.mobagent.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.i18n.AppLanguage
import com.mobagent.app.i18n.tr
import com.mobagent.app.model.ApiClient
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.launch

private const val MAX_COMMENT = 500
private const val MAX_REPLY_SENT = 3500
private const val FALLBACK_EMAIL = "mob.agent.app@gmail.com"

/**
 * Google Play (AI-Generated Content siyosati): AI javobini ilova ICHIDAN xabar qilish.
 * Sabab tanlanadi, shikoyat serverga (admin panel -> Fikrlar, turi "report") yuboriladi.
 * Hisobga kirilmagan bo'lsa yoki server javob bermasa — elektron pochta orqali yuborish taklif qilinadi.
 * Yuboriladigan narsa: tanlangan sabab, ixtiyoriy izoh va FAQAT shikoyat qilingan AI javobi
 * (suhbatning qolgan qismi yuborilmaydi).
 */
@Composable
fun ReportDialog(aiReply: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reasons = listOf(
        "harmful" to tr("Zararli yoki xavfli"),
        "inappropriate" to tr("Nomaqbul (haqorat, jinsiy va h.k.)"),
        "incorrect" to tr("Noto'g'ri yoki chalg'ituvchi"),
        "other" to tr("Boshqa sabab")
    )
    var reason by remember { mutableStateOf<String?>(null) }
    var comment by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var offerEmail by remember { mutableStateOf(false) }
    val fieldShape = RoundedCornerShape(Sizing.radiusSm)

    fun body(): String = buildString {
        append("[").append(reason ?: "other").append("]")
        if (comment.isNotBlank()) append(' ').append(comment.trim())
        append("\n--- AI ---\n")
        append(aiReply.take(MAX_REPLY_SENT))
    }

    fun sendByEmail() {
        val mail = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FALLBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, tr("MobAgent: AI javobi haqida shikoyat"))
            putExtra(Intent.EXTRA_TEXT, body().take(4500))
        }
        runCatching { context.startActivity(Intent.createChooser(mail, tr("Shikoyat qilish"))) }
        onDismiss()
    }

    fun send() {
        sending = true
        error = null
        scope.launch {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            val res = ApiClient.sendFeedback("report", body(), "", version, AppLanguage.effective.code)
            sending = false
            when (res) {
                ApiClient.FeedbackResult.Ok -> {
                    Toast.makeText(context, tr("Rahmat! Shikoyatingiz yuborildi"), Toast.LENGTH_LONG).show()
                    onDismiss()
                }
                ApiClient.FeedbackResult.RateLimited -> error = tr("Juda ko'p fikr yuborildi, keyinroq urinib ko'ring")
                ApiClient.FeedbackResult.NeedLogin, ApiClient.FeedbackResult.Failed -> {
                    error = tr("Ilova ichidan yuborib bo'lmadi. Elektron pochta orqali yuborishingiz mumkin.")
                    offerEmail = true
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = BgCardElevated,
        title = { Text(tr("AI javobi haqida shikoyat"), color = TextPrimary, fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("Sababni tanlang:"), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                reasons.forEach { (k, label) ->
                    val on = reason == k
                    Text(
                        label,
                        color = if (on) AccentPurpleText else TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(Sizing.radiusSm))
                            .background(if (on) AccentPurpleBg else BgApp)
                            .border(1.dp, if (on) AccentPurple else BorderColor, RoundedCornerShape(Sizing.radiusSm))
                            .clickable(enabled = !sending) { reason = k; error = null }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 70.dp).clip(fieldShape).background(BgApp)
                        .border(1.dp, BorderColor, fieldShape).padding(12.dp)
                ) {
                    if (comment.isEmpty()) {
                        Text(tr("Izoh (ixtiyoriy)"), color = TextMuted, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = comment,
                        onValueChange = { if (it.length <= MAX_COMMENT) comment = it },
                        enabled = !sending,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(AccentPurple),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Faqat shu AI javobi va sababingiz yuboriladi; suhbatning qolgan qismi yuborilmaydi."),
                    color = TextMuted, fontSize = 11.sp
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = DangerText, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (offerEmail) {
                TextButton(onClick = { sendByEmail() }) {
                    Text(tr("Pochta orqali yuborish"), color = AccentPurpleText)
                }
            } else {
                val canSend = !sending && reason != null
                TextButton(onClick = { send() }, enabled = canSend) {
                    Text(
                        if (sending) tr("Yuborilmoqda…") else tr("Yuborish"),
                        color = if (canSend) AccentPurpleText else TextMuted
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text(tr("Bekor qilish"), color = TextSecondary) }
        }
    )
}
