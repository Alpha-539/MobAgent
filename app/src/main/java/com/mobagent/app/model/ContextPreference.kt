package com.mobagent.app.model

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Model o'qiy oladigan eng katta kontekst (llama-android.cpp'dagi MAX_CTX bilan bir xil). */
const val MAX_CONTEXT = 16384

/**
 * Foydalanuvchi Sozlamalar -> Kontekst orqali tanlagan kontekst uzunligi (token).
 * Barcha modellar uchun umumiy; modelning o'z chegarasi bo'lsa (ModelItem.maxContext),
 * o'shanga tushiriladi. O'zgarish keyingi ochiladigan suhbatdan boshlanadi
 * (model shu paytda yangi hajm bilan qayta yuklanadi).
 */
object ContextPreference {
    private const val PREFS = "mobagent_context"
    private const val KEY = "n_ctx"

    /** Tanlash mumkin bo'lgan qiymatlar. */
    val STEPS = listOf(4096, 8192, 12288, 16384)

    var value by mutableIntStateOf(STANDARD_CTX)
        private set

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, STANDARD_CTX)
        value = if (saved in STEPS) saved else STANDARD_CTX
    }

    fun set(context: Context, ctx: Int) {
        val safe = if (ctx in STEPS) ctx else STANDARD_CTX
        value = safe
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, safe).apply()
    }
}
