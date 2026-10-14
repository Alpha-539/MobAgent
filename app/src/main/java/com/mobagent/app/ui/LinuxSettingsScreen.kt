package com.mobagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.i18n.mirrorInRtl
import com.mobagent.app.i18n.tr
import com.mobagent.app.linux.LinuxEnvironment
import com.mobagent.app.linux.LinuxInstallService
import com.mobagent.app.linux.LinuxInstaller
import com.mobagent.app.permission.hasStorageAccess
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Sozlamalar > Linux muhiti (Ubuntu + proot, UserLAnd uslubida).
 * O'rnatish, yoqish/o'chirish, xotirani ulash, sinov va o'chirish shu yerda.
 */
@Composable
fun LinuxSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cardShape = RoundedCornerShape(Sizing.radiusMd)

    val installerState by LinuxInstaller.state.collectAsState()
    var installed by remember { mutableStateOf(LinuxEnvironment.isInstalled(context)) }
    var enabled by remember { mutableStateOf(LinuxEnvironment.isEnabled(context)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val runtimeOk = remember { LinuxEnvironment.isRuntimeAvailable(context) }

    // O'rnatish (qo'lda yoki avtomatik) tugagach holatni yangilash. Yoqishni o'rnatuvchining o'zi qiladi.
    LaunchedEffect(installerState) {
        if (installerState is LinuxInstaller.State.Done) {
            installed = LinuxEnvironment.isInstalled(context)
            enabled = LinuxEnvironment.isEnabled(context)
        }
    }

    val running = installerState is LinuxInstaller.State.Checking ||
        installerState is LinuxInstaller.State.Downloading ||
        installerState is LinuxInstaller.State.Verifying ||
        installerState is LinuxInstaller.State.Extracting ||
        installerState is LinuxInstaller.State.Configuring

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
            Text(tr("Linux muhiti"), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            tr("Agent buyruqlari Ubuntu ichida bajariladi: apt, python3, git va boshqalar ishlaydi. Ixtiyoriy — o'chiq bo'lsa agent avvalgidek ishlaydi."),
            color = TextSecondary, fontSize = 13.sp
        )

        // Holat
        Spacer(Modifier.height(16.dp))
        Card(cardShape) {
            Text(tr("Holat"), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            val status = when {
                !runtimeOk -> tr("Bu APK'da proot yo'q (qurilish skripti ishlamagan)")
                installed -> tr("Ubuntu o'rnatilgan")
                else -> tr("Ubuntu o'rnatilmagan")
            }
            Text(status, color = if (runtimeOk) TextSecondary else DangerText, fontSize = 13.sp)

            when (val st = installerState) {
                is LinuxInstaller.State.Downloading -> {
                    Spacer(Modifier.height(10.dp))
                    if (st.total > 0) {
                        LinearProgressIndicator(progress = { (st.done.toFloat() / st.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(tr("Yuklanmoqda: %1\$s / %2\$s MB", st.done / 1_000_000, st.total / 1_000_000), color = TextMuted, fontSize = 12.sp)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(tr("Ulanilmoqda..."), color = TextMuted, fontSize = 12.sp)
                    }
                }
                is LinuxInstaller.State.Checking -> Progress(tr("Tekshirilmoqda..."))
                is LinuxInstaller.State.Verifying -> Progress(tr("Tekshirilmoqda (SHA-256)..."))
                is LinuxInstaller.State.Extracting -> Progress(tr("Ochilmoqda: %1\$s fayl", st.files))
                is LinuxInstaller.State.Configuring -> Progress(tr("Sozlanmoqda..."))
                is LinuxInstaller.State.Failed -> {
                    Spacer(Modifier.height(10.dp))
                    Text(tr("Xato: %1\$s", st.message), color = DangerText, fontSize = 12.sp)
                }
                else -> Unit
            }
            // Avtomatik urinish muvaffaqiyatsiz bo'lgan bo'lsa, sababi shu yerda ko'rinadi
            if (!installed && !running && installerState !is LinuxInstaller.State.Failed) {
                LinuxEnvironment.lastError(context)?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(tr("Oxirgi urinish xatosi: %1\$s", it), color = DangerText, fontSize = 12.sp)
                }
            }

            if (!installed && runtimeOk) {
                Spacer(Modifier.height(12.dp))
                if (running) {
                    TextButton(onClick = {
                        LinuxEnvironment.setAutoOptedOut(context, true) // bekor qilgan — o'zi qayta boshlamaydi
                        LinuxInstaller.cancel()
                    }) { Text(tr("Bekor qilish")) }
                } else {
                    TextButton(onClick = { LinuxInstallService.start(context, auto = false) }) {
                        Text(tr("O'rnatish"))
                    }
                }
            }
        }

        if (installed) {
            Spacer(Modifier.height(12.dp))
            Card(cardShape) {
                ToggleRow(
                    title = tr("Linux muhitini yoqish"),
                    subtitle = tr("Yoqilganda agent buyruqlari Ubuntu ichida ishlaydi"),
                    checked = enabled
                ) {
                    enabled = it
                    LinuxEnvironment.setEnabled(context, it)
                }
                Text(
                    tr("Sozlama keyingi yangi terminal sessiyasida kuchga kiradi."),
                    color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            Card(cardShape) {
                Text(tr("Sinov"), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(tr("proot ishga tushishini, Ubuntu versiyasini va apt ni tekshiradi."), color = TextMuted, fontSize = 12.sp)
                TextButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val r = LinuxEnvironment.selfTest(context)
                            testResult = "exit=${r.exitCode}\n${r.output}"
                            testing = false
                        }
                    }
                ) { Text(if (testing) tr("Tekshirilmoqda...") else tr("Sinovni boshlash")) }
                testResult?.let {
                    Text(
                        it, color = CodeBlockText, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CodeBlockBg).padding(10.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(cardShape).background(BgCardElevated).border(1.dp, BorderColor, cardShape)
                    .clickable { confirmRemove = true }.padding(16.dp)
            ) {
                Text(tr("Linux muhitini o'chirish"), color = DangerText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = BgCardElevated,
            title = { Text(tr("Linux muhiti o'chirilsinmi?"), color = TextPrimary, fontSize = 17.sp) },
            text = { Text(tr("Ubuntu va unda o'rnatilgan hamma narsa o'chadi. Telefon xotirasi fayllariga tegilmaydi."), color = TextSecondary, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        LinuxEnvironment.removeByUser(context)
                        LinuxInstaller.resetState()
                        installed = false
                        enabled = false
                        testResult = null
                    }
                }) { Text(tr("O'chirish"), color = DangerText) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(tr("Bekor qilish")) } }
        )
    }
}

@Composable
private fun Card(shape: RoundedCornerShape, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(shape).background(BgCardElevated).border(1.dp, BorderColor, shape).padding(16.dp),
        content = content
    )
}

@Composable
private fun Progress(text: String) {
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(text, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
