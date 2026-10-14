package com.mobagent.app.ui

import com.mobagent.app.i18n.mirrorInRtl
import com.mobagent.app.i18n.tr
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.auth.AuthRepository
import com.mobagent.app.auth.AuthResult
import com.mobagent.app.auth.DeleteResult
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.launch

private const val NAME_MAX = 40

/**
 * Profil: rasm (galereyadan), ism, hisob ma'lumoti va profilni o'chirish.
 * Sozlamalar -> Profil orqali ochiladi.
 */
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = AuthRepository.user
    val hasPhoto = remember(ProfileStore.version, user?.uid) { ProfileStore.exists(context) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            if (!ProfileStore.saveFrom(context, uri)) {
                Toast.makeText(context, tr("Rasmni o'qib bo'lmadi"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Ism
    val savedName = AuthRepository.displayName.orEmpty()
    var nameText by remember(savedName) { mutableStateOf(savedName) }
    var saving by remember { mutableStateOf(false) }
    val canSave = !saving && nameText.isNotBlank() && nameText.trim() != savedName.trim()

    // Profilni o'chirish
    var showDelete by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val needsPassword = AuthRepository.hasPassword && !AuthRepository.isGoogleUser

    Column(
        Modifier.fillMaxSize().background(BgApp).statusBarsPadding().navigationBarsPadding()
            .imePadding().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ArrowBackIosNew, contentDescription = tr("Orqaga"), tint = TextSecondary,
                modifier = Modifier.size(16.dp).clickable(onClick = onBack).mirrorInRtl()
            )
            Spacer(Modifier.width(14.dp))
            Text(tr("Profil"), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }

        // Rasm
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clip(CircleShape).clickable { pickImage.launch("image/*") }) {
                ProfileAvatar(size = 110.dp, fontSize = 40.sp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(BgCardElevated)
                        .border(1.dp, BorderColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = tr("Rasm tanlash"), tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (hasPhoto) tr("Rasm shu telefonda saqlanadi") else if (AuthRepository.isGoogleUser) tr("Google hisobingiz rasmi ko'rsatilmoqda") else tr("Rasm tanlash uchun doirani bosing"),
                color = TextMuted, fontSize = 12.sp
            )
            if (hasPhoto) {
                Text(
                    tr("Rasmni olib tashlash"), color = DangerText, fontSize = 13.sp,
                    modifier = Modifier.clickable { ProfileStore.remove(context) }.padding(8.dp)
                )
            }
        }

        // Ism
        Spacer(Modifier.height(20.dp))
        SectionTitle(tr("Ism"))
        OutlinedTextField(
            value = nameText,
            onValueChange = { nameText = it.take(NAME_MAX).replace('\n', ' ') },
            singleLine = true,
            placeholder = { Text(tr("Ismingizni kiriting"), color = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Sizing.radiusMd),
            colors = fieldColors()
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(Sizing.radiusMd))
                .background(if (canSave) BrandPurple else BgCardElevated)
                .clickable(enabled = canSave) {
                    saving = true
                    scope.launch {
                        val res = AuthRepository.updateDisplayName(nameText)
                        saving = false
                        val msg = when (res) {
                            is AuthResult.Success -> tr("Ism saqlandi")
                            is AuthResult.Error -> res.message
                            is AuthResult.Cancelled -> null
                        }
                        if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (saving) tr("Saqlanmoqda…") else tr("Saqlash"),
                color = if (canSave) Color.White else TextMuted,
                fontSize = 15.sp, fontWeight = FontWeight.Medium
            )
        }
        Text(
            tr("Ism faqat shu ilovada o'zgaradi; Google hisobingizdagi ism o'zgarmaydi."),
            color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp)
        )

        // Hisob ma'lumoti
        Spacer(Modifier.height(24.dp))
        SectionTitle(tr("Hisob"))
        val cardShape = RoundedCornerShape(Sizing.radiusMd)
        Column(
            Modifier.fillMaxWidth().clip(cardShape).background(BgCardElevated).border(1.dp, BorderColor, cardShape)
        ) {
            InfoRow(tr("Email"), user?.email ?: "—")
            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
            InfoRow(
                tr("Kirish usuli"),
                when {
                    AuthRepository.isGoogleUser -> "Google"
                    AuthRepository.hasPassword -> tr("Email va parol")
                    else -> "—"
                }
            )
        }

        // Profilni o'chirish
        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth().clip(cardShape).background(BgCardElevated).border(1.dp, BorderColor, cardShape)
                .clickable {
                    password = ""
                    deleteError = null
                    showDelete = true
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = DangerText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(tr("Profilni o'chirish"), color = DangerText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Text(
            tr("Hisobingiz butunlay o'chiriladi. Bu amalni qaytarib bo'lmaydi."),
            color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp)
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDelete = false },
            containerColor = BgCardElevated,
            title = { Text(tr("Profilni o'chirish"), color = TextPrimary, fontSize = 17.sp) },
            text = {
                Column {
                    Text(
                        tr("Hisobingiz va bulutdagi onlayn suhbatlar tarixi butunlay o'chiriladi va qayta tiklab bo'lmaydi. Oflayn suhbatlar va yuklab olingan modellar telefonda qoladi."),
                        color = TextSecondary, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Pullik tarifingiz bo'lsa, avval Google Play > Obunalar bo'limidan uni bekor qiling: hisob o'chgach to'lov to'xtamaydi va tarif qaytarilmaydi."),
                        color = TextSecondary, fontSize = 13.sp
                    )
                    if (needsPassword) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; deleteError = null },
                            singleLine = true,
                            enabled = !deleting,
                            label = { Text(tr("Tasdiqlash uchun parol")) },
                            visualTransformation = PasswordVisualTransformation(),
                            colors = fieldColors()
                        )
                    } else if (AuthRepository.isGoogleUser) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            tr("Xavfsizlik uchun Google hisobingizni qayta tanlashingiz so'ralishi mumkin."),
                            color = TextMuted, fontSize = 12.sp
                        )
                    }
                    deleteError?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = DangerText, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting && (!needsPassword || password.isNotEmpty()),
                    onClick = {
                        deleting = true
                        deleteError = null
                        val uid = user?.uid
                        scope.launch {
                            val res = AuthRepository.deleteAccount(
                                activityContext = context,
                                password = if (needsPassword) password else null,
                                onDeleted = { if (uid != null) ProfileStore.wipeFor(context, uid) }
                            )
                            deleting = false
                            when (res) {
                                is DeleteResult.Error -> deleteError = res.message
                                // Muvaffaqiyat: hisob o'chgach ilova o'zi Kirish ekraniga o'tadi.
                                is DeleteResult.Success, is DeleteResult.Cancelled -> Unit
                            }
                        }
                    }
                ) { Text(if (deleting) tr("O'chirilmoqda…") else tr("O'chirish"), color = DangerText) }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { showDelete = false }) {
                    Text(tr("Bekor qilish"), color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = AccentPurple,
    unfocusedBorderColor = BorderColor
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.width(16.dp))
        Text(
            value, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
