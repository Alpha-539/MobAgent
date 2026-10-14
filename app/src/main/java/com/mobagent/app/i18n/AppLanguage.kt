package com.mobagent.app.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/** Ilova tillari. `nativeName` — til o'z nomi bilan yoziladi (ro'yxatda hamma tilda bir xil). */
enum class AppLang(val code: String, val nativeName: String, val rtl: Boolean = false) {
    UZ("uz", "O'zbekcha"),
    RU("ru", "Русский"),
    EN("en", "English"),
    AR("ar", "العربية", rtl = true);

    companion object {
        fun fromCode(code: String?): AppLang? = values().firstOrNull { it.code == code }
    }
}

/**
 * Ilova tili. `choice == null` — "Tizim tili": telefon tiliga ergashadi
 * (uz / ru / ar / en); qo'llab-quvvatlanmagan til bo'lsa — inglizcha.
 *
 * `choice` Compose holati, shuning uchun til almashganda barcha ekranlar
 * qayta ishga tushmasdan darhol yangilanadi.
 */
object AppLanguage {
    private const val PREFS = "mobagent_language"
    private const val KEY = "lang"

    var choice by mutableStateOf<AppLang?>(null)
        private set

    private var loaded = false

    /** Bir necha marta chaqirilsa ham bo'ladi (MainActivity va servislar chaqiradi). */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        choice = AppLang.fromCode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )
    }

    /** `null` — tizim tili. */
    fun set(context: Context, lang: AppLang?) {
        loaded = true
        choice = lang
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (lang == null) editor.remove(KEY) else editor.putString(KEY, lang.code)
        editor.apply()
    }

    /** Hozir ishlatilayotgan til. */
    val effective: AppLang
        get() = choice ?: systemLang()

    fun systemLang(): AppLang = when (Locale.getDefault().language) {
        "uz" -> AppLang.UZ
        "ru" -> AppLang.RU
        "ar" -> AppLang.AR
        else -> AppLang.EN
    }
}

/**
 * Tarjima: kalit — o'zbekcha asl matn. Joriy til o'zbekcha bo'lsa yoki jadvalda
 * tarjima topilmasa, asl matn qaytadi (ilova hech qachon qulamaydi).
 */
fun tr(uz: String): String {
    val lang = AppLanguage.effective
    if (lang == AppLang.UZ) return uz
    return Translations.forLang(lang)[uz] ?: uz
}

/** Parametrli tarjima: matnda `%1$s`, `%2$s` kabi joylar bo'ladi. */
fun tr(uz: String, vararg args: Any?): String {
    val template = tr(uz)
    return try {
        String.format(Locale.ROOT, template, *args)
    } catch (e: Exception) {
        template
    }
}

internal object Translations {
    fun forLang(lang: AppLang): Map<String, String> = when (lang) {
        AppLang.RU -> TRANSLATIONS_RU
        AppLang.EN -> TRANSLATIONS_EN
        AppLang.AR -> TRANSLATIONS_AR
        AppLang.UZ -> emptyMap()
    }
}

/** Yo'nalishli ikonkalarni (masalan, "orqaga" strelkasi) arabchada ko'zguda aks ettiradi. */
@Composable
fun Modifier.mirrorInRtl(): Modifier =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) this.graphicsLayer(scaleX = -1f) else this
