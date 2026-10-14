package com.mobagent.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Ilova dizayni endi ikkita rejimni qo'llab-quvvatlaydi: TUNGI (qora, logotipdagi
 * kabi) va TONGI/kunduzgi (asosiy rang OQ, logotipdagi binafsha esa aksent
 * sifatida saqlanadi). Ilgari faqat bitta qattiq kodlangan qorong'i palitra bor
 * edi (darkColorScheme, o'zgarmas top-level `val`lar) — shuning uchun tizim
 * yorug' rejimda bo'lsa ham ilova doim qorong'i ko'rinardi.
 *
 * Ekranlardagi mavjud kod (masalan `color = BgCard`) o'zgarishsiz ishlashi uchun,
 * har bir rang endi oddiy sobit qiymat emas, balki joriy rejimni
 * LocalMobAgentColors orqali o'qiydigan @Composable getter — shuning uchun
 * boshqa hech qaysi ekran faylini o'zgartirish shart bo'lmadi.
 */
data class MobAgentColors(
    val bgApp: Color,
    val bgCard: Color,
    val bgCardAlt: Color,
    val bgCardElevated: Color,
    val borderColor: Color,
    val borderColorStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentPurple: Color,
    val brand: Color,
    val accentPurpleDeep: Color,
    val accentPurpleBg: Color,
    val accentPurpleText: Color,
    val successGreen: Color,
    val successGreenBg: Color,
    val successGreenText: Color,
    val popularBg: Color,
    val popularText: Color,
    val dangerText: Color,
    // Model kategoriyalari uchun qo'shimcha ranglar ("Popular" belgisi o'rniga —
    // endi har bir model qaysi yo'nalishga mos ekanligini har xil rangda ko'rsatadi).
    val codingBg: Color,
    val codingText: Color,
    val reasoningBg: Color,
    val reasoningText: Color,
    // Kod bloklari (MarkdownText.kt) va model-oilasi bo'yicha avatar ranglari
    // (ChatScreen.kt'dagi AgentAvatar) — ilgari bular umuman yo'q edi.
    val codeBlockBg: Color,
    val codeBlockText: Color,
    val chatFamilyBg: Color,
    val chatFamilyText: Color,
    val translateFamilyBg: Color,
    val translateFamilyText: Color
)

// TUNGI REJIM — logotipdagi qora karta + binafsha nur.
val DarkMobAgentColors = MobAgentColors(
    bgApp = Color(0xFF121118),
    bgCard = Color(0xFF1C1B25),
    bgCardAlt = Color(0xFF17161E),
    bgCardElevated = Color(0xFF272532),
    borderColor = Color(0xFF32303F),
    borderColorStrong = Color(0xFF494660),
    textPrimary = Color(0xFFF3F2F8),
    textSecondary = Color(0xFFB1AFC2),
    textMuted = Color(0xFF7C798E),
    accentPurple = Color(0xFF8B7CF6),
    brand = Color(0xFF8B7CF6),
    accentPurpleDeep = Color(0xFF6E5EE8),
    accentPurpleBg = Color(0xFF2B2657),
    accentPurpleText = Color(0xFFDCD6FF),
    successGreen = Color(0xFF3DD68C),
    successGreenBg = Color(0xFF163C2C),
    successGreenText = Color(0xFF7CEAB4),
    popularBg = Color(0xFF4A2E06),
    popularText = Color(0xFFFFC876),
    dangerText = Color(0xFFF16A69),
    codingBg = Color(0xFF123A52),
    codingText = Color(0xFF7CC6F2),
    reasoningBg = Color(0xFF4A1740),
    reasoningText = Color(0xFFF29CE0),
    codeBlockBg = Color(0xFF0E0D14),
    codeBlockText = Color(0xFFE0E0E6),
    chatFamilyBg = Color(0xFF1B3A2E),
    chatFamilyText = Color(0xFF7CE2B0),
    translateFamilyBg = Color(0xFF3A2A12),
    translateFamilyText = Color(0xFFF2B96E)
)

// TONGI / KUNDUZGI REJIM — asosiy rang OQ (foydalanuvchi so'ragan), aksent
// xuddi shu binafsha (logotipdagi rang) — faqat kartalar va matn kontrasti
// yorug' fonga moslashtirilgan.
val LightMobAgentColors = MobAgentColors(
    bgApp = Color(0xFFFAF9FD),
    bgCard = Color(0xFFFFFFFF),
    bgCardAlt = Color(0xFFF3F1FA),
    bgCardElevated = Color(0xFFECE9F8),
    borderColor = Color(0xFFE4E1EF),
    borderColorStrong = Color(0xFFD0CCE3),
    textPrimary = Color(0xFF1E1B2E),
    textSecondary = Color(0xFF5C586F),
    textMuted = Color(0xFF928EA4),
    accentPurple = Color(0xFF5B3FD9),
    brand = Color(0xFF4B31C9),
    accentPurpleDeep = Color(0xFF4B31C9),
    accentPurpleBg = Color(0xFFE9E4FB),
    accentPurpleText = Color(0xFF4B31C9),
    successGreen = Color(0xFF1FAE6B),
    successGreenBg = Color(0xFFDFF7EA),
    successGreenText = Color(0xFF14824E),
    popularBg = Color(0xFFFFEBCF),
    popularText = Color(0xFF9C6100),
    dangerText = Color(0xFFD8433F),
    codingBg = Color(0xFFDCEEFC),
    codingText = Color(0xFF15628F),
    reasoningBg = Color(0xFFFBE3F5),
    reasoningText = Color(0xFF9C1F82),
    codeBlockBg = Color(0xFF1D1D22),
    codeBlockText = Color(0xFFE6E6EC),
    chatFamilyBg = Color(0xFFDDF3E7),
    chatFamilyText = Color(0xFF16794A),
    translateFamilyBg = Color(0xFFFBEAD3),
    translateFamilyText = Color(0xFF8A5A12)
)

val LocalMobAgentColors = staticCompositionLocalOf { DarkMobAgentColors }

// Ilgari bularning barchasi o'zgarmas `val` edi. Endi har biri joriy rejimga
// mos ranglarni LocalMobAgentColors orqali o'qiydigan @Composable getter —
// mavjud ekranlardagi `color = BgCard` kabi ishlatilishlar o'zgarishsiz qoladi.
val BgApp: Color @Composable get() = LocalMobAgentColors.current.bgApp
val BgCard: Color @Composable get() = LocalMobAgentColors.current.bgCard
val BgCardAlt: Color @Composable get() = LocalMobAgentColors.current.bgCardAlt
val BgCardElevated: Color @Composable get() = LocalMobAgentColors.current.bgCardElevated
val BorderColor: Color @Composable get() = LocalMobAgentColors.current.borderColor
val BorderColorStrong: Color @Composable get() = LocalMobAgentColors.current.borderColorStrong
val TextPrimary: Color @Composable get() = LocalMobAgentColors.current.textPrimary
val TextSecondary: Color @Composable get() = LocalMobAgentColors.current.textSecondary
val TextMuted: Color @Composable get() = LocalMobAgentColors.current.textMuted

val AccentPurple: Color @Composable get() = LocalMobAgentColors.current.accentPurple
val BrandPurple: Color @Composable get() = LocalMobAgentColors.current.brand
val AccentPurpleDeep: Color @Composable get() = LocalMobAgentColors.current.accentPurpleDeep
val AccentPurpleBg: Color @Composable get() = LocalMobAgentColors.current.accentPurpleBg
val AccentPurpleText: Color @Composable get() = LocalMobAgentColors.current.accentPurpleText

val SuccessGreen: Color @Composable get() = LocalMobAgentColors.current.successGreen
val SuccessGreenBg: Color @Composable get() = LocalMobAgentColors.current.successGreenBg
val SuccessGreenText: Color @Composable get() = LocalMobAgentColors.current.successGreenText

val PopularBg: Color @Composable get() = LocalMobAgentColors.current.popularBg
val PopularText: Color @Composable get() = LocalMobAgentColors.current.popularText

val CodingBg: Color @Composable get() = LocalMobAgentColors.current.codingBg
val CodingText: Color @Composable get() = LocalMobAgentColors.current.codingText
val ReasoningBg: Color @Composable get() = LocalMobAgentColors.current.reasoningBg
val ReasoningText: Color @Composable get() = LocalMobAgentColors.current.reasoningText

val DangerText: Color @Composable get() = LocalMobAgentColors.current.dangerText

val CodeBlockBg: Color @Composable get() = LocalMobAgentColors.current.codeBlockBg
val CodeBlockText: Color @Composable get() = LocalMobAgentColors.current.codeBlockText
val ChatFamilyBg: Color @Composable get() = LocalMobAgentColors.current.chatFamilyBg
val ChatFamilyText: Color @Composable get() = LocalMobAgentColors.current.chatFamilyText
val TranslateFamilyBg: Color @Composable get() = LocalMobAgentColors.current.translateFamilyBg
val TranslateFamilyText: Color @Composable get() = LocalMobAgentColors.current.translateFamilyText

/** Katta kompaniyalar ilovalarida odatiy bo'lgan gradient — asosiy CTA tugmalarida. */
val AccentGradient: Brush
    @Composable get() = Brush.horizontalGradient(
        listOf(LocalMobAgentColors.current.accentPurple, LocalMobAgentColors.current.accentPurple)
    )
val HeroGlow: Brush
    @Composable get() {
        val c = LocalMobAgentColors.current.accentPurple
        return Brush.radialGradient(listOf(c.copy(alpha = 0.14f), c.copy(alpha = 0f)))
    }

/** Butun ilova bo'ylab bir xil burchak/oraliq shkalasi — tasodifiy qiymatlar o'rniga. */
object Sizing {
    val radiusSm = 10.dp
    val radiusMd = 14.dp
    val radiusLg = 20.dp
    val radiusPill = 999.dp
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val spaceMd = 12.dp
    val spaceLg = 16.dp
    val spaceXl = 24.dp
}

/**
 * @param darkTheme null bo'lsa tizim rejimiga (kun/tun) ergashadi — foydalanuvchi
 * ilova ichida qo'lda tanlasa (ThemePreference), o'sha qiymat ustunlik qiladi.
 */
@Composable
fun MobAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkMobAgentColors else LightMobAgentColors
    val materialScheme = if (darkTheme) {
        darkColorScheme(
            background = palette.bgApp,
            surface = palette.bgCard,
            primary = palette.accentPurple,
            onPrimary = palette.textPrimary,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            error = palette.dangerText
        )
    } else {
        lightColorScheme(
            background = palette.bgApp,
            surface = palette.bgCard,
            primary = palette.accentPurple,
            onPrimary = Color.White,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            error = palette.dangerText
        )
    }
    // Qo'lda tanlangan rejim (ThemePreference) tizim rejimidan farq qilsa ham,
    // status/navigatsiya paneli fon va belgilari ilova rangiga mos bo'lsin.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = palette.bgApp.toArgb()
            window.navigationBarColor = palette.bgApp.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalMobAgentColors provides palette) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = if (FontPreference.useSystem) SystemTypography else MobAgentTypography,
            content = content
        )
    }
}
