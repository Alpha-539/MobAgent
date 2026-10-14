package com.mobagent.app.linux

import android.content.Context
import android.net.ConnectivityManager

/**
 * Ilova ochilganda: Ubuntu'ni o'zi o'rnatishni boshlash kerakmi? (Roziliq dialogi yo'q — avtomatik.)
 * Faqat qaror beradi; ishni [LinuxInstallService] bajaradi.
 */
object LinuxAutoInstall {
    enum class Decision { START, ASK, SKIP }

    private const val MIN_FREE_BYTES = 600L * 1024 * 1024

    /** Bloklovchi fayl/tarmoq tekshiruvlari bor — fon tredda chaqiring. */
    fun decide(context: Context): Decision {
        if (!LinuxEnvironment.isRuntimeAvailable(context)) return Decision.SKIP // APK'da proot yo'q
        if (LinuxEnvironment.isInstalled(context) || LinuxInstaller.isRunning()) return Decision.SKIP
        if (!LinuxEnvironment.autoInstallAllowed(context)) return Decision.SKIP // o'chirgan yoki 2 marta xato
        if (context.filesDir.usableSpace < MIN_FREE_BYTES) return Decision.SKIP
        // APK ichida rootfs bo'lsa internet kerak emas; aks holda yuklash uchun tarmoq kerak (keyingi ochilishda qayta tekshiriladi).
        if (!LinuxInstaller.hasBundledRootfs(context) && !isOnline(context)) return Decision.SKIP

        // UserLAnd kabi: ilova birinchi ochilganda Ubuntu o'zi o'rnatiladi (dialogsiz, fonda).
        // Foydalanuvchi Sozlamalardan o'chirgan bo'lsa (autoInstallAllowed) qayta o'rnatilmaydi.
        return Decision.START
    }

    private fun isOnline(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork != null
    } catch (e: Exception) {
        false
    }
}
