package com.mobagent.app.ui

import com.mobagent.app.i18n.AppLang
import com.mobagent.app.i18n.AppLanguage
import com.mobagent.app.i18n.mirrorInRtl
import com.mobagent.app.i18n.tr
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import com.mobagent.app.linux.LinuxEnvironment
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.auth.AuthRepository
import com.mobagent.app.model.ContextPreference
import com.mobagent.app.model.ModelCatalog
import com.mobagent.app.model.downloadedModelFile
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TELEGRAM_URL = "https://t.me/AnonimDasturchi"

private enum class SettingsDialog { Theme, Font, Context, Language, Feedback }

/**
 * Sozlamalar: Profil, Modellar, Kontekst, Til (tez orada), Rang rejimi, Shrift,
 * Bog'lanish va Hisobdan chiqish.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenLinux: () -> Unit = {},
    onOpenBilling: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = AuthRepository.user
    val cardShape = RoundedCornerShape(Sizing.radiusMd)
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    val downloadedCount = remember { ModelCatalog.models.count { downloadedModelFile(context, it.id) != null } }
    val languageLabel = if (AppLanguage.choice == null)
        tr("Tizim tili") + " · " + AppLanguage.effective.nativeName
    else AppLanguage.effective.nativeName
    val themeLabel = when (ThemePreference.override) {
        null -> tr("Tizim")
        false -> tr("Tongi")
        true -> tr("Tungi")
    }

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
            Text(tr("Sozlamalar"), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }

        // Profil
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth().clip(cardShape).background(BgCardElevated).border(1.dp, BorderColor, cardShape)
                .clickable(onClick = onOpenProfile)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatar(size = 52.dp, fontSize = 20.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val name = AuthRepository.displayName?.takeIf { it.isNotBlank() }
                Text(
                    name ?: tr("Profil"), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(user?.email.orEmpty(), color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Chevron()
        }

        // Billing: tarif holati va to'lovlar (profil kartasining tagida)
        var billingPlan by remember { mutableStateOf<com.mobagent.app.billing.CloudPlan?>(null) }
        LaunchedEffect(user?.uid) { billingPlan = com.mobagent.app.billing.CloudApi.plan() }
        val billingSubtitle = when (billingPlan?.planId) {
            "basic" -> tr("Tarifingiz: %1\$s", tr("Boshlang'ich"))
            "standard" -> tr("Tarifingiz: %1\$s", tr("O'rta"))
            "pro" -> tr("Tarifingiz: %1\$s", "Pro")
            else -> if (billingPlan != null) tr("Faol tarif yo'q") else tr("Tarif va to'lovlar")
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(cardShape).background(BgCardElevated).border(1.dp, BorderColor, cardShape)) {
            SettingsRow(Icons.Filled.CreditCard, "Billing", billingSubtitle, onClick = onOpenBilling)
        }

        // Bo'limlar
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().clip(cardShape).background(BgCardElevated).border(1.dp, BorderColor, cardShape)) {
            SettingsRow(
                Icons.Filled.Memory, tr("Modellar"),
                if (downloadedCount == 0) tr("Yuklangan model yo'q") else tr("%1\$s ta yuklangan", downloadedCount),
                onClick = onOpenModels
            )
            RowDivider()
            SettingsRow(
                Icons.Filled.Terminal, tr("Linux muhiti"),
                when {
                    !LinuxEnvironment.isInstalled(context) -> tr("O'rnatilmagan")
                    LinuxEnvironment.isEnabled(context) -> tr("Ubuntu · yoqilgan")
                    else -> tr("Ubuntu · o'chiq")
                },
                onClick = onOpenLinux
            )
            RowDivider()
            SettingsRow(Icons.Filled.Tune, tr("Kontekst (oflayn rejim)"), tr("%1\$sK token", ContextPreference.value / 1024)) {
                dialog = SettingsDialog.Context
            }
            RowDivider()
            SettingsRow(Icons.Filled.Language, tr("Til"), languageLabel) { dialog = SettingsDialog.Language }
            RowDivider()
            SettingsRow(Icons.Filled.Palette, tr("Rang rejimi"), themeLabel) { dialog = SettingsDialog.Theme }
            RowDivider()
            SettingsRow(Icons.Filled.TextFields, tr("Shrift"), if (FontPreference.useSystem) tr("Tizim") else tr("Standart")) {
                dialog = SettingsDialog.Font
            }
            RowDivider()
            SettingsRow(Icons.Filled.Feedback, tr("Fikr bildirish"), tr("Taklif yoki xato haqida yozing")) {
                dialog = SettingsDialog.Feedback
            }
            RowDivider()
            SettingsRow(Icons.Filled.Send, tr("Bog'lanish"), tr("Telegram · @AnonimDasturchi")) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL)))
                } catch (e: Exception) {
                    Toast.makeText(context, tr("Telegram yoki brauzer topilmadi"), Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Hisob
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth().clip(cardShape).background(BgCardElevated).border(1.dp, BorderColor, cardShape)
                .clickable { scope.launch { AuthRepository.signOut(context) } }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = DangerText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(tr("Hisobdan chiqish"), color = DangerText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))
    }

    when (dialog) {
        SettingsDialog.Theme -> ChoiceDialog(
            title = tr("Rang rejimi"),
            options = listOf(
                ChoiceOption(tr("Tizim"), tr("Telefon sozlamasiga ergashadi")),
                ChoiceOption(tr("Tongi")),
                ChoiceOption(tr("Tungi"))
            ),
            selectedIndex = when (ThemePreference.override) { null -> 0; false -> 1; true -> 2 },
            onSelect = { i ->
                ThemePreference.set(context, when (i) { 1 -> false; 2 -> true; else -> null })
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.Font -> ChoiceDialog(
            title = tr("Shrift"),
            options = listOf(
                ChoiceOption(tr("Tizim"), tr("Telefoningizning o'z shrifti")),
                ChoiceOption(tr("Standart"), tr("MobAgent shrifti (Space Grotesk)"))
            ),
            selectedIndex = if (FontPreference.useSystem) 0 else 1,
            onSelect = { i ->
                FontPreference.set(context, i == 0)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.Language -> ChoiceDialog(
            title = tr("Til"),
            options = listOf(
                ChoiceOption(
                    tr("Tizim tili"),
                    tr("Telefon tiliga ergashadi (hozir: %1\$s)", AppLanguage.systemLang().nativeName)
                )
            ) + AppLang.values().map { ChoiceOption(it.nativeName) },
            selectedIndex = AppLanguage.choice?.let { AppLang.values().indexOf(it) + 1 } ?: 0,
            onSelect = { i ->
                AppLanguage.set(context, if (i == 0) null else AppLang.values()[i - 1])
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.Context -> ContextDialog(onDismiss = { dialog = null })
        SettingsDialog.Feedback -> FeedbackDialog(onDismiss = { dialog = null })
        null -> Unit
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(22.dp))
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) TextSecondary else TextMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) TextPrimary else TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (enabled) Chevron()
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(1.dp).background(BorderColor))
}

private data class ChoiceOption(val label: String, val hint: String? = null)

/** Bitta variantni tanlash oynasi: bosilgan zahoti saqlanadi va yopiladi. */
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<ChoiceOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCardElevated,
        title = { Text(title, color = TextPrimary, fontSize = 17.sp) },
        text = {
            Column {
                options.forEachIndexed { i, option ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Sizing.radiusSm))
                            .clickable { onSelect(i) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = i == selectedIndex,
                            onClick = { onSelect(i) },
                            colors = RadioButtonDefaults.colors(selectedColor = AccentPurple, unselectedColor = TextMuted)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(option.label, color = TextPrimary, fontSize = 15.sp)
                            option.hint?.let { Text(it, color = TextMuted, fontSize = 12.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Yopish"), color = TextSecondary) } }
    )
}

/** Kontekst uzunligini o'zi sozlash: 4K / 8K / 12K / 16K. */
@Composable
private fun ContextDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val steps = ContextPreference.STEPS
    var index by remember { mutableIntStateOf(steps.indexOf(ContextPreference.value).coerceAtLeast(0)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCardElevated,
        title = { Text(tr("Kontekst uzunligi (oflayn rejim)"), color = TextPrimary, fontSize = 17.sp) },
        text = {
            Column {
                Text(
                    tr("%1\$sK token", steps[index] / 1024), color = AccentPurpleText,
                    fontSize = 22.sp, fontWeight = FontWeight.Medium
                )
                Slider(
                    value = index.toFloat(),
                    onValueChange = { index = it.roundToInt().coerceIn(0, steps.lastIndex) },
                    valueRange = 0f..steps.lastIndex.toFloat(),
                    steps = steps.size - 2,
                    colors = SliderDefaults.colors(
                        thumbColor = AccentPurple,
                        activeTrackColor = AccentPurple,
                        inactiveTrackColor = BorderColorStrong,
                        activeTickColor = AccentPurple,
                        inactiveTickColor = BorderColorStrong
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    steps.forEach { Text("${it / 1024}K", color = TextMuted, fontSize = 11.sp) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    tr("Kontekst qancha uzun bo'lsa, model suhbatni shuncha ko'p eslab qoladi, lekin ko'proq RAM ishlatadi va sekinroq ishlaydi. 8K dan yuqori qiymatlar kuchsiz telefonlarda ilovani to'xtatib qo'yishi mumkin."),
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Bu sozlama faqat oflayn (telefondagi) modellarga tegishli. Onlayn modellar o'z xizmatining katta kontekstidan foydalanadi."),
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("O'zgarish keyingi ochiladigan suhbatdan boshlanadi. Ba'zi modellar (masalan, Gemma-2-2B) o'zi 8K bilan cheklangan."),
                    color = TextMuted, fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ContextPreference.set(context, steps[index])
                onDismiss()
            }) { Text(tr("Saqlash"), color = AccentPurpleText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Bekor qilish"), color = TextSecondary) } }
    )
}
