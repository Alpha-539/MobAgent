package com.mobagent.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shrift tanlovi (Sozlamalar -> Shrift):
 *  - useSystem = false: MobAgent shrifti (Space Grotesk) — standart;
 *  - useSystem = true: telefonning tizim shrifti.
 * Kod va terminal matni (JetBrains Mono) ikkala holatda ham o'zgarmaydi.
 */
object FontPreference {
    private const val PREFS = "mobagent_font"
    private const val KEY = "use_system"

    var useSystem by mutableStateOf(false)
        private set

    fun init(context: Context) {
        useSystem = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun set(context: Context, system: Boolean) {
        useSystem = system
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, system).apply()
    }
}
