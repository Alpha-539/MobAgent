package com.mobagent.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Foydalanuvchi tungi/tongi rejimni Home ekranidagi quyosh/oy tugmasi orqali
 * qo'lda tanlashi mumkin. `override == null` bo'lsa qurilmaning tizim
 * sozlamasiga (kun/tun) ergashiladi — bu standart holat.
 *
 * Ilgari ilova doim qattiq kodlangan bitta qorong'i rejimda ishlardi, endi
 * bu tanlov SharedPreferences'da saqlanadi va ilova qayta ochilganda ham
 * eslab qolinadi.
 */
object ThemePreference {
    private const val PREFS = "mobagent_theme"
    private const val KEY = "mode" // 0 = hali tanlanmagan (tizimga ergashadi), 1 = tongi, 2 = tungi

    var override by mutableStateOf<Boolean?>(null)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override = when (prefs.getInt(KEY, 0)) {
            1 -> false
            2 -> true
            else -> null
        }
    }

    private fun persist(context: Context, value: Boolean?) {
        override = value
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = when (value) {
            false -> 1
            true -> 2
            null -> 0
        }
        prefs.edit().putInt(KEY, code).apply()
    }

    /**
     * Quyosh/oy tugmasi bosilganda: hozirgi ko'rinib turgan rejimning teskarisiga
     * o'tadi (faqat 2 ta rejim: tungi <-> tongi). Foydalanuvchi hali hech narsa
     * tanlamagan bo'lsa, ilova birinchi marta tizim rejimiga ergashadi.
     */
    /** Sozlamalardan: null = tizimga ergashish, false = tongi, true = tungi. */
    fun set(context: Context, value: Boolean?) = persist(context, value)

    fun toggle(context: Context, currentlyDark: Boolean) {
        persist(context, !currentlyDark)
    }
}
