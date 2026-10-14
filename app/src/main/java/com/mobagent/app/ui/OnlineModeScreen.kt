package com.mobagent.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import com.mobagent.app.i18n.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.mobagent.app.online.ApiKeyStore
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.R
import com.mobagent.app.ui.theme.*

sealed class OnlineChoice {
    /** Kalit ApiKeyStore'da saqlanadi (navigatsiya holatida ochiq matn aylanmasligi uchun). */
    data class OwnApiKey(val providerId: String, val model: String) : OnlineChoice()
    object AppProvided : OnlineChoice()
}

@Composable
fun OnlineModeScreen(
    onBack: () -> Unit,
    onConfirm: (OnlineChoice) -> Unit,
    onOpenApiKeys: () -> Unit,
    onMenuClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val savedKeys = remember(com.mobagent.app.auth.AuthRepository.user?.uid) { ApiKeyStore.savedCount(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
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
        // Login sahifasidagi bilan bir xil logotip (>_< + MobAgent)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LoginBrand(large = false)
        }
        Spacer(Modifier.height(20.dp))
        Text(tr("Onlayn rejim"), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(tr("Serverdagi kuchli model ishlaydi"), color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))

        // 1-variant (tepada): MobAgent agents — kalit kerak emas
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(BgCard)
                .border(0.5.dp, AccentPurple, RoundedCornerShape(14.dp))
                .clickable { onConfirm(OnlineChoice.AppProvided) }
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MobAgent agents", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentPurpleBg)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(tr("Pullik"), color = AccentPurpleText, fontSize = 11.sp)
                }
            }
            Text(tr("Kalit kerak emas, obuna orqali ishlaydi"), color = TextSecondary, fontSize = 12.sp)
        }

        Spacer(Modifier.height(12.dp))

        // 2-variant (pastda): o'z API kalitlari — alohida sahifa ochiladi
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(BgCard)
                .border(0.5.dp, BorderColor, RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenApiKeys)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("O'z API kalitim bilan"), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(tr("OpenAI, DeepSeek, OpenRouter, Groq va boshqa xizmatlar kalitlari, bepul"), color = TextSecondary, fontSize = 12.sp)
                if (savedKeys > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(tr("Saqlangan kalitlar: %1\$s", savedKeys), color = AccentPurpleText, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("›", color = TextMuted, fontSize = 20.sp)
        }
    }
}
