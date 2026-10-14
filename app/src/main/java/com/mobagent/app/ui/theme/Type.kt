package com.mobagent.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont

/**
 * ILOVANING SHRIFT TIZIMI (3 ta shrift, aniq vazifalar bilan):
 *
 *  1) SpaceGroteskFamily — INTERFEYS (tugmalar, kartalar, oddiy matn). Claude/
 *     Anthropic'ning o'z shrifti (Styrene) litsenziyalangan bo'lgani uchun uni
 *     bevosita ishlata olmaymiz — Space Grotesk xuddi shunday "geometrik,
 *     kvadratsimon" xarakterga ega, bepul va ochiq (Google Fonts).
 *  2) NewsreaderFamily — SARLAVHA/BREND lahzalari (masalan HomeScreen'dagi
 *     "MobAgent" nomi va shiori). Claude'ning Tiempos (adabiy serif) shriftiga
 *     muqobil — xuddi shu "jurnal sarlavhasi" hissi uchun maxsus yaratilgan.
 *  3) JetBrainsMonoFamily — KOD/TERMINAL matni (JSON, bash buyruqlar, "Natija
 *     (kod N):" bloklari). Ismi ham mos keladi — MobAgent dasturchi vositasi.
 *
 * MUHIM — OFLAYN SAVOLI: bu uchta shrift hozircha Android'ning "Downloadable
 * Fonts" (GoogleFont provider) orqali ulangan — ya'ni ilova birinchi marta
 * shu shriftni chizishda Google Play Services orqali bir martalik yuklab
 * oladi (keyin qurilmada keshlanadi, boshqa hech qachon tarmoq kerak
 * bo'lmaydi). Bu ilovaning "modelni ham oflayn ishlatish" falsafasiga 100%
 * mos emas — agar buni ham to'liq oflayn (hech qachon hech narsa yuklamaydigan)
 * qilish kerak bo'lsa, quyidagi https://fonts.google.com/specimen/... sahifa-
 * laridan .ttf fayllarni qo'lda yuklab, shu papkaga (res/font/) joylashtirib,
 * pastdagi FontFamily'larni Font(R.font.space_grotesk_medium) kabi lokal
 * resurslarga almashtirish kifoya — qolgan butun kod (Theme.kt, ekranlar)
 * o'zgarishsiz qoladi.
 */

private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = com.mobagent.app.R.array.com_google_android_gms_fonts_certs
)

private fun googleFontFamily(name: String): FontFamily {
    val font = GoogleFont(name)
    return FontFamily(
        Font(googleFont = font, fontProvider = googleFontProvider, weight = FontWeight.Normal),
        Font(googleFont = font, fontProvider = googleFontProvider, weight = FontWeight.Medium),
        Font(googleFont = font, fontProvider = googleFontProvider, weight = FontWeight.Bold)
    )
}

val SpaceGroteskFamily: FontFamily = googleFontFamily("Space Grotesk")
val NewsreaderFamily: FontFamily = googleFontFamily("Newsreader")
val JetBrainsMonoFamily: FontFamily = googleFontFamily("JetBrains Mono")

/**
 * Butun ilova bo'ylab standart shrift. Ekranlardagi ko'pchilik `Text(...)`
 * chaqiruvlari hech qanday `style=` bermaydi — ular Compose'ning
 * `LocalTextStyle` (MaterialTheme.typography'dan keladi) orqali standart
 * shriftni meros qilib oladi. Shuning uchun shu yerda fontFamily'ni
 * SpaceGrotesk qilib qo'yish — HAR BIR ekran faylini alohida o'zgartirmasdan,
 * butun ilova shriftini bir joydan almashtiradi.
 */
private fun buildTypography(family: FontFamily): Typography {
    val base = TextStyle(fontFamily = family)
    return Typography(
        displayLarge = base.copy(fontWeight = FontWeight.Medium),
        displayMedium = base.copy(fontWeight = FontWeight.Medium),
        displaySmall = base.copy(fontWeight = FontWeight.Medium),
        headlineLarge = base.copy(fontWeight = FontWeight.Medium),
        headlineMedium = base.copy(fontWeight = FontWeight.Medium),
        headlineSmall = base.copy(fontWeight = FontWeight.Medium),
        titleLarge = base.copy(fontWeight = FontWeight.Medium),
        titleMedium = base.copy(fontWeight = FontWeight.Medium),
        titleSmall = base.copy(fontWeight = FontWeight.Medium),
        bodyLarge = base,
        bodyMedium = base,
        bodySmall = base,
        labelLarge = base.copy(fontWeight = FontWeight.Medium),
        labelMedium = base.copy(fontWeight = FontWeight.Medium),
        labelSmall = base.copy(fontWeight = FontWeight.Medium)
    )
}

/** MobAgent shrifti (Space Grotesk) — standart. */
val MobAgentTypography: Typography = buildTypography(SpaceGroteskFamily)

/** Telefonning tizim shrifti (Sozlamalar -> Shrift -> Tizim). */
val SystemTypography: Typography = buildTypography(FontFamily.Default)

/** Tanlovga qarab joriy asosiy shrift oilasi (Theme va logotip matnlari uchun). */
@androidx.compose.runtime.Composable
fun appFontFamily(): FontFamily = if (FontPreference.useSystem) FontFamily.Default else SpaceGroteskFamily
