package com.mobagent.app.ui

import com.mobagent.app.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.auth.AuthRepository
import com.mobagent.app.auth.AuthResult
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Email/parol bilan ro'yxatdan o'tgan foydalanuvchi emailini tasdiqlamaguncha
 * shu ekran ko'rsatiladi. Tasdiqlanishi har 3 soniyada avtomatik tekshiriladi;
 * tasdiqlangach AppRoot o'zi Home'ga o'tkazadi.
 */
@Composable
fun VerifyEmailScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var cooldown by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            AuthRepository.refreshVerified()
        }
    }
    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgApp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LoginBrand(large = false)
                Spacer(Modifier.height(32.dp))
                Text2(tr("Emailingizni tasdiqlang"), 22, TextPrimary, FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Text2(
                    tr("%1\$s manziliga tasdiqlash havolasini yubordik. Xatdagi havolani bosing, so'ng shu yerga qayting.", AuthRepository.user?.email.orEmpty()),
                    14, TextSecondary, FontWeight.Normal
                )
                Spacer(Modifier.height(8.dp))
                Text2(tr("Xat kelmasa, Spam papkasini tekshiring."), 12, TextMuted, FontWeight.Normal)
            }

            error?.let { Text2(it, 12, DangerText, FontWeight.Normal); Spacer(Modifier.height(12.dp)) }
            info?.let { Text2(it, 12, SuccessGreenText, FontWeight.Normal); Spacer(Modifier.height(12.dp)) }

            PillButton(filled = true, enabled = !loading, onClick = {
                error = null; info = null; loading = true
                scope.launch {
                    val ok = AuthRepository.refreshVerified()
                    loading = false
                    if (!ok) error = tr("Email hali tasdiqlanmagan. Xatdagi havolani bosing.")
                }
            }) {
                if (loading) {
                    CircularProgressIndicator(color = BgApp, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    androidx.compose.material3.Text(tr("Tasdiqladim"), color = BgApp, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(12.dp))
            PillButton(filled = false, enabled = !loading && cooldown == 0, onClick = {
                error = null; info = null; loading = true
                scope.launch {
                    val r = AuthRepository.resendVerification()
                    loading = false
                    if (r is AuthResult.Error) error = r.message
                    else { info = tr("Xat qayta yuborildi."); cooldown = 60 }
                }
            }) {
                androidx.compose.material3.Text(
                    if (cooldown > 0) tr("Xatni qayta yuborish (%1\$s)", cooldown) else tr("Xatni qayta yuborish"),
                    color = if (cooldown > 0) TextMuted else TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Text(
                tr("Boshqa hisob bilan kirish"),
                color = AccentPurpleText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { scope.launch { AuthRepository.signOut(context) } }
            )
        }
    }
}

@Composable
private fun Text2(text: String, size: Int, color: androidx.compose.ui.graphics.Color, weight: FontWeight) {
    androidx.compose.material3.Text(
        text,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
