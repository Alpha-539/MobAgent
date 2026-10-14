package com.mobagent.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.billing.BillingManager
import com.mobagent.app.billing.CloudApi
import com.mobagent.app.billing.CloudPlan
import com.mobagent.app.i18n.tr
import com.mobagent.app.model.ApiConfig
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun planTitle(productId: String): String = when (productId) {
    "mobagent_basic" -> tr("Boshlang'ich")
    "mobagent_standard" -> tr("O'rta")
    "mobagent_pro" -> "Pro"
    else -> productId
}

private fun planIdTitle(planId: String?): String = when (planId) {
    "basic" -> tr("Boshlang'ich")
    "standard" -> tr("O'rta")
    "pro" -> "Pro"
    else -> "—"
}

private fun fmt(ms: Long): String =
    if (ms <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

@Composable
fun CloudPlansScreen(
    onBack: () -> Unit,
    onOpenModel: (id: String, name: String) -> Unit,
    onMenuClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<CloudPlan?>(null) }
    var planLoaded by remember { mutableStateOf(false) }
    var offers by remember { mutableStateOf<List<BillingManager.PlanOffers>?>(null) }
    var offersLoaded by remember { mutableStateOf(false) }
    var creditOffers by remember { mutableStateOf<List<BillingManager.CreditOffer>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val verifiedTick by BillingManager.verified.collectAsState()

    // Mavjud xaridlarni serverga qayta yuborish + narxlarni olish
    LaunchedEffect(Unit) {
        BillingManager.restore()
        offers = BillingManager.loadOffers()
        creditOffers = BillingManager.loadCreditOffers()
        offersLoaded = true
    }
    LaunchedEffect(verifiedTick) {
        plan = CloudApi.plan()
        planLoaded = true
    }
    LaunchedEffect(Unit) {
        BillingManager.messages.collect { message = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("‹ Orqaga"), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onBack))
            if (onMenuClick != null) {
                Icon(
                    Icons.Filled.Menu, contentDescription = tr("Menyu"), tint = TextSecondary,
                    modifier = Modifier.size(22.dp).clickable(onClick = onMenuClick)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("MobAgent agents", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(tr("Kuchli onlayn modellar, kalit kerak emas"), color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        message?.let {
            Text(it, color = AccentPurpleText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
        }

        val p = plan
        if (p?.planId != null || (p?.credits ?: 0L) > 0L) {
            // ---- Faol tarif / credits: foydalanish va modellar ----
            if (p?.planId != null) {
                Card {
                    Text(tr("Tarifingiz: %1\$s", planIdTitle(p.planId)), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(tr("Amal qiladi: %1\$s gacha", fmt(p.validUntil)), color = TextSecondary, fontSize = 11.sp)
                    Spacer(Modifier.height(12.dp))
                    UsageBar(tr("5 soatlik limit"), p.windowUsed, p.windowResetsAt)
                    UsageBar(tr("Haftalik limit"), p.weekUsed, p.weekResetsAt)
                    UsageBar(tr("Oylik limit"), p.monthUsed, p.monthResetsAt)
                }
                Spacer(Modifier.height(12.dp))
            }
            if (p != null && p.credits > 0L) {
                Card {
                    Text(tr("Credits: %1\$s", p.credits.toString()), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(tr("Tarif limiti tugagach avtomatik ishlatiladi"), color = TextSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(tr("Modelni tanlang"), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            // Modellar ro'yxati uzun bo'lib sahifani to'ldirmasin: 5 tasi ko'rinadi (qator ~56dp), qolgani ichki scroll bilan.
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                p?.models.orEmpty().sortedWith(compareBy({ it.locked }, { it.tier != "medium" })).forEach { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgCard)
                            .border(0.5.dp, if (m.locked) BorderColor else AccentPurple, RoundedCornerShape(12.dp))
                            .then(if (m.locked) Modifier else Modifier.clickable { onOpenModel(m.id, m.name) })
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            m.name, color = if (m.locked) TextMuted else TextPrimary, fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            when {
                                m.locked -> tr("Yuqoriroq tarifda")
                                m.free -> tr("Bepul")
                                m.tier == "strong" -> tr("Kuchli")
                                else -> tr("O'rtacha")
                            },
                            color = TextMuted, fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tr("Obunani boshqarish / bekor qilish"),
                color = AccentPurpleText, fontSize = 12.sp,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions")))
                    }
                }
            )
            Spacer(Modifier.height(24.dp))
        } else if (planLoaded && p == null) {
            Text(tr("Tarif holatini olib bo'lmadi. Internetni tekshiring."), color = DangerText, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
        }

        // ---- Bepul model (tarifsiz ham, kirgan foydalanuvchi uchun) ----
        val frees = p?.freeModels?.takeIf { it.isNotEmpty() } ?: listOfNotNull(p?.free)
        if (frees.isNotEmpty()) {
            Card {
                Text(if (p?.planId != null) tr("5 soatda %1\$s token", fmtTokens(frees.first().limit)) else tr("Tarifsiz ham ishlaydi: 5 soatda %1\$s token", fmtTokens(frees.first().limit)), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                // Barcha bepul modellar uchun BITTA umumiy limit (hisoblagich server tomonida umumiy).
                val f = frees.first()
                val left = (f.limit - f.used).coerceAtLeast(0)
                UsageBar(
                    tr("Qolgan tokenlar: %1\$s / %2\$s", fmtTokens(left), fmtTokens(f.limit)),
                    (f.used.toDouble() / f.limit.coerceAtLeast(1)), f.resetsAt
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // ---- Tariflar (xarid) ----
        Text(
            if (p?.planId != null) tr("Tarifni o'zgartirish") else tr("Tarifni tanlang"),
            color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        val list = offers
        when {
            !offersLoaded -> Text(tr("Yuklanmoqda..."), color = TextMuted, fontSize = 12.sp)
            list.isNullOrEmpty() -> Text(
                tr("Obunalar hozir mavjud emas. Ilovani Google Play orqali o'rnating va yangilang."),
                color = TextMuted, fontSize = 12.sp
            )
            else -> list.forEach { po ->
                Card {
                    Text(planTitle(po.productId), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        po.monthly?.let { o ->
                            BuyButton(tr("%1\$s / oy", o.price), Modifier.weight(1f)) {
                                val act = context.findActivity()
                                if (act == null || !BillingManager.launch(act, o)) message = tr("Xarid oynasini ochib bo'lmadi")
                            }
                        }
                        po.yearly?.let { o ->
                            BuyButton(tr("%1\$s / yil", o.price), Modifier.weight(1f)) {
                                val act = context.findActivity()
                                if (act == null || !BillingManager.launch(act, o)) message = tr("Xarid oynasini ochib bo'lmadi")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        // ---- Credits (bir martalik xarid) ----
        val credList = creditOffers
        if (!credList.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(tr("Credits sotib olish"), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(tr("Tarif limiti tugaganda ishlatiladi. Muddati cheklanmagan."), color = TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                credList.forEach { o ->
                    BuyButton(o.price, Modifier.weight(1f)) {
                        val act = context.findActivity()
                        if (act == null || !BillingManager.launchCredits(act, o)) message = tr("Xarid oynasini ochib bo'lmadi")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Google Play talab qiladigan ma'lumotlar: avtomatik uzaytirish, bekor qilish yo'li, maxfiylik
        Spacer(Modifier.height(8.dp))
        Text(
            tr("Obuna tanlangan davr (oy yoki yil) oxirida shu narxda avtomatik uzaytiriladi. Istalgan vaqtda Google Play > Obunalar bo'limidan bekor qilishingiz mumkin; bekor qilsangiz, to'langan davr tugaguncha ishlaydi."),
            color = TextMuted, fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Suhbatlaringiz serverimiz va AI provayder (Anthropic, OpenAI yoki Google) orqali o'tadi; serverda saqlanmaydi."),
            color = TextMuted, fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Maxfiylik siyosati"), color = AccentPurpleText, fontSize = 11.sp,
            modifier = Modifier.clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ApiConfig.BASE_URL + "/privacy"))) }
            }
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .border(0.5.dp, BorderColor, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun UsageBar(label: String, used: Double, resetsAt: Long) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            Text("${(used * 100).toInt().coerceIn(0, 100)}%", color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { used.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (used >= 0.9) DangerText else AccentPurple,
            trackColor = BgCardAlt
        )
        if (used >= 0.9 && resetsAt > 0) {
            Text(tr("Tiklanadi: %1\$s", fmt(resetsAt)), color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun BuyButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AccentPurple)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}


/** Token sonini qisqa ko'rsatadi: 950 -> "950", 12300 -> "12.3K", 1500000 -> "1.5M". */
private fun fmtTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1_000.0).replace(".0K", "K")
    else -> n.toString()
}
