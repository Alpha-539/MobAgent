package com.mobagent.app.agent

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import java.io.File

/**
 * Termux qanday ishlashini takrorlaydi: root talab qilmasdan, katta to'plam
 * UNIX buyruqlarini (ls, grep, sed, tar, find va h.k.) taqdim etadi.
 *
 * Mexanizm:
 *  1) CI (.github/workflows/build.yml) toybox'ni (github.com/landley/toybox)
 *     NDK orqali arm64-v8a uchun cross-compile qiladi va natijani
 *     app/src/main/jniLibs/arm64-v8a/libtoybox.so sifatida joylashtiradi.
 *     "lib*.so" nomlanishi shart — shundagina Android uni o'rnatishda
 *     ilova bilan birga EXEC huquqi bilan haqiqiy faylga ajratadi
 *     (applicationInfo.nativeLibraryDir ostida), root yoki alohida
 *     "chmod +x" talab qilmasdan (Android'ning W^X siyosati shunga imkon
 *     beradi, chunki fayl paket o'rnatilishida platforma tomonidan
 *     joylashtirilgan bo'ladi).
 *  2) toybox — busybox kabi "multi-call" binary: argv[0] (chaqirilgan
 *     nomi) qaysi applet ekanini bildiradi. Shuning uchun bu yerda har bir
 *     applet nomi uchun ilovaning shaxsiy papkasida libtoybox.so'ga
 *     symlink yaratamiz ("ls" -> libtoybox.so), xuddi Termux $PREFIX/bin
 *     qilgani kabi.
 *  3) CommandExecutor shu papkani PATH'ning boshiga qo'shadi — shunda
 *     "grep"/"sed"/"tar" kabi buyruqlar Android'ning cheklangan
 *     /system/bin versiyalari o'rniga to'liq toybox versiyasini topadi.
 *
 * Qurilma ABI'si uchun libtoybox.so topilmasa (masalan, CI hali build
 * qilmagan bo'lsa yoki eski APK), bu yerdagi hamma narsa jimgina
 * o'chirilgan holatda qoladi — CommandExecutor odatdagidek
 * /system/bin/sh orqali ishlashda davom etadi.
 */
object ToyboxProvisioner {

    // Termuxning $PREFIX/bin'idagi eng ko'p ishlatiladigan applet'larga
    // o'xshash to'plam. To'liq ro'yxat uchun: libtoybox.so'ni
    // "toybox --list" bilan ishga tushiring va shu yerni kengaytiring.
    private val APPLETS = listOf(
        "ls", "cat", "grep", "egrep", "fgrep", "sed", "awk", "find", "xargs",
        "tar", "gzip", "gunzip", "zcat", "unzip", "cut", "sort", "uniq", "wc",
        "head", "tail", "tee", "du", "df", "ps", "top", "free", "mkdir", "rm",
        "rmdir", "cp", "mv", "ln", "chmod", "chown", "touch", "echo", "printf",
        "base64", "md5sum", "sha1sum", "sha256sum", "cksum", "diff", "patch",
        "which", "env", "date", "sleep", "wget", "ping", "od", "tr", "xxd",
        "basename", "dirname", "readlink", "realpath", "stat", "file", "yes",
        "seq", "expr", "test", "true", "false", "split", "comm", "join",
        "paste", "fold", "fmt", "nl", "column", "strings", "hexdump", "id",
        "whoami", "uname", "pwd", "unix2dos", "dos2unix", "vi"
    )

    private const val MARKER_PREFIX = ".installed-"

    fun binDir(context: Context): File = File(context.filesDir, "toybox/bin")

    fun homeDir(context: Context): File = File(context.filesDir, "toybox/home").apply { mkdirs() }

    private fun toyboxBinary(context: Context): File? {
        val f = File(context.applicationInfo.nativeLibraryDir, "libtoybox.so")
        return if (f.exists() && f.canExecute()) f else null
    }

    /** Diskda toybox mavjudligini bildiradi (CommandExecutor shu bo'yicha qaror qiladi). */
    fun isAvailable(context: Context): Boolean = toyboxBinary(context) != null

    /**
     * Bir martalik: har applet nomi uchun libtoybox.so'ga symlink yaratadi.
     * Idempotent — allaqachon o'rnatilgan bo'lsa, hech narsa qilmaydi.
     * Chaqiruvchi tomon (CommandExecutor) buni fon tredda chaqirishi kerak
     * (fayl amaliyoti — asosiy tredda emas).
     */
    fun ensureInstalled(context: Context): Boolean {
        val bin = toyboxBinary(context) ?: return false
        val dir = binDir(context)
        val marker = File(dir, "$MARKER_PREFIX${bin.length()}")
        if (marker.exists()) return true

        dir.mkdirs()
        var allOk = true
        for (applet in APPLETS) {
            val link = File(dir, applet)
            try {
                // Eski/eskirgan symlink bo'lsa, avval tozalaymiz (masalan
                // libtoybox.so yangilangan build bo'lsa).
                if (link.exists()) link.delete()
                Os.symlink(bin.absolutePath, link.absolutePath)
            } catch (e: ErrnoException) {
                allOk = false
            }
        }
        if (allOk) {
            dir.listFiles { f -> f.name.startsWith(MARKER_PREFIX) }?.forEach { it.delete() }
            marker.createNewFile()
        }
        return allOk
    }

    /** CommandExecutor shu qiymatni PATH oldiga qo'shadi (agar mavjud bo'lsa). */
    fun pathPrefix(context: Context): String? {
        val dir = binDir(context)
        return if (dir.exists() && dir.listFiles()?.isNotEmpty() == true) dir.absolutePath else null
    }
}
