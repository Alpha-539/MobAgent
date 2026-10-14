package com.mobagent.app.ui

import com.mobagent.app.i18n.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.mobagent.app.i18n.AppLanguage
import com.mobagent.app.model.Announcement
import com.mobagent.app.model.AppUpdate
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.mobagent.app.auth.AuthRepository
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.R
import com.mobagent.app.ui.theme.*

@Composable
fun HomeScreen(
    onOnlineClick: () -> Unit,
    onOfflineClick: () -> Unit,
    onMenuClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDarkNow = ThemePreference.override ?: isSystemInDarkTheme()
    // Yangi versiya eslatmasi: bosh sahifa ochilganda bir marta tekshiriladi (xato bo'lsa jim).
    LaunchedEffect(Unit) { AppUpdate.refresh(context) }
    Box(modifier = Modifier.fillMaxSize().background(BgApp)) {
        // Yumshoq gradient nur — zamonaviy ilovalarning start ekranlarida
        // keng tarqalgan "hero glow" effekti (ilgari tekis, tekis fon edi).

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(HeroGlow)
        )

        // Yon panelni ochish tugmasi (chap yuqori burchak). Hisobdan chiqish endi
        // Sozlamalar ekranida (profil rasmi orqali).
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 20.dp, start = 20.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(BgCardElevated)
                .border(1.dp, BorderColor, CircleShape)
                .clickable(onClick = onMenuClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Menu, contentDescription = tr("Menyu"), tint = TextSecondary, modifier = Modifier.size(20.dp))
        }

        // Tungi/tongi rejim tugmasi. Ilgari ilova doim bitta qattiq kodlangan
        // qorong'i rejimda edi — endi shu yerdan qo'lda almashtirish mumkin
        // (tizim -> tongi -> tungi -> tizim ... aylanadi).
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 20.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(BgCardElevated)
                .border(1.dp, BorderColor, CircleShape)
                .clickable { ThemePreference.toggle(context, isDarkNow) },
            contentAlignment = Alignment.Center
        ) {
            val icon = if (isDarkNow) Icons.Filled.DarkMode else Icons.Filled.LightMode
            Icon(icon, contentDescription = tr("Rejimni almashtirish"), tint = AccentPurple, modifier = Modifier.size(18.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 32.dp)
        ) {
            // Yuqoridagi menyu/rejim tugmalari (40dp) ostida e'lon banneri
            Spacer(Modifier.height(40.dp))
            AppUpdate.visible(context)?.let { u ->
                Spacer(Modifier.height(12.dp))
                AnnouncementBanner(u, onDismiss = { AppUpdate.dismiss(context, u) })
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Login sahifasidagi bilan bir xil logotip (>_< + MobAgent)
                LoginBrand(large = true)
                Spacer(Modifier.height(16.dp))
                Text(tr("Telefoningizdagi AI-agent"), color = TextSecondary, fontSize = 14.sp)
            }

            ModeCard(
                icon = Icons.Filled.CloudQueue,
                iconTint = AccentPurple,
                iconBg = AccentPurpleBg,
                title = tr("Onlayn rejim"),
                subtitle = tr("Internet orqali kuchli model"),
                onClick = onOnlineClick
            )
            Spacer(Modifier.height(12.dp))
            ModeCard(
                icon = Icons.Filled.PhoneAndroid,
                iconTint = SuccessGreen,
                iconBg = SuccessGreenBg,
                title = tr("Oflayn rejim"),
                subtitle = tr("Modelni yuklab, internetsiz ishlating"),
                onClick = onOfflineClick
            )

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = TextMuted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("Internetsiz ham, internet bilan ham ishlaydi"), color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Sizing.radiusLg))
            .background(BgCardElevated)
            .border(1.dp, BorderColor, RoundedCornerShape(Sizing.radiusLg))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AnnouncementBanner(a: Announcement, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val (bg, fg) = when (a.level) {
        "warn" -> PopularBg to PopularText
        "promo" -> SuccessGreenBg to SuccessGreenText
        else -> AccentPurpleBg to AccentPurpleText
    }
    val shape = RoundedCornerShape(Sizing.radiusLg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .then(
                if (a.linkUrl != null) Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.linkUrl))) }
                } else Modifier
            )
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(a.title, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (a.body.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(a.body, color = fg, fontSize = 12.sp)
            }
        }
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = tr("Yopish"), tint = fg, modifier = Modifier.size(16.dp))
        }
    }
}
