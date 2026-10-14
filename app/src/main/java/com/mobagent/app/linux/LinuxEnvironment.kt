package com.mobagent.app.linux

import android.content.Context
import android.os.Environment
import android.system.Os
import com.mobagent.app.agent.ExecutionResult
import com.mobagent.app.permission.hasStorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * UserLAnd uslubidagi Linux muhiti: Ubuntu rootfs + `proot`.
 *
 * MEXANIZM (nega bu Android 10+ da ishlaydi):
 *  - Android 10+ da ilova o'z ma'lumotlar papkasidagi faylni `execve` qila olmaydi
 *    (W^X). Lekin `nativeLibraryDir` dagi `lib*.so` fayllar APK o'rnatilganda
 *    platforma tomonidan joylanadi va ularni ishga tushirish MUMKIN.
 *  - Shu sabab `proot`, uning `loader`i va `libtalloc` jniLibs ichida
 *    `libproot.so`, `libproot-loader.so`, `libtalloc.so` nomi bilan keladi
 *    (scripts/fetch-proot.sh, CI).
 *  - Ubuntu ichidagi dasturlarni kernel emas, proot'ning loader'i yuklaydi,
 *    shuning uchun ularning o'zi "exec" qilinmaydi.
 *
 * Bu muhit ixtiyoriy: o'rnatilmagan/yoqilmagan bo'lsa, agent eski
 * (toybox + Termux) yo'lida ishlayveradi.
 */
object LinuxEnvironment {
    private const val PREFS = "mobagent_linux"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SHARE_SDCARD = "share_sdcard"
    private const val MARKER = ".mobagent-installed"
    private const val KEY_OPTED_OUT = "auto_opted_out"
    private const val KEY_AUTO_FAILS = "auto_fails"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_CONSENT = "install_consent"
    /** Avtomatik o'rnatish shuncha marta muvaffaqiyatsiz bo'lsa, boshqa urinmaydi (qo'lda o'rnatish ochiq qoladi). */
    private const val MAX_AUTO_FAILS = 2

    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun baseDir(context: Context) = File(context.filesDir, "linux")
    fun rootfsDir(context: Context) = File(baseDir(context), "rootfs")
    private fun supportDir(context: Context) = File(baseDir(context), "support")
    fun markerFile(context: Context) = File(rootfsDir(context), MARKER)

    private fun nativeLib(context: Context, name: String) = File(context.applicationInfo.nativeLibraryDir, name)

    /** APK ichida proot + loader + talloc bormi (CI skripti ishlagan bo'lsa bor). */
    fun isRuntimeAvailable(context: Context): Boolean =
        nativeLib(context, "libproot.so").let { it.exists() && it.canExecute() } &&
            nativeLib(context, "libproot-loader.so").exists() &&
            nativeLib(context, "libtalloc.so").exists() &&
            nativeLib(context, "libandroid-shmem.so").exists()

    fun isInstalled(context: Context): Boolean = markerFile(context).exists()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Telefon xotirasini Linux ichiga /sdcard sifatida ulash.
     * Foydalanuvchi aniq tanlamagan bo'lsa — "Barcha fayllarga kirish" ruxsati bor-yo'qligiga ergashadi:
     * ruxsat bo'lsa, eski (toybox) shell ham /sdcard'ga to'liq kira olardi, ya'ni yangi imkoniyat qo'shilmaydi.
     */
    /** Google Play uchun: telefon xotirasi Linux ichiga ulanmaydi (umumiy xotiraga kirish ruxsati yo'q). */
    fun isSdcardShared(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false
    fun setSdcardShared(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHARE_SDCARD, value).apply()

    // ---- Avtomatik o'rnatish holati ----

    fun autoInstallAllowed(context: Context): Boolean {
        val p = prefs(context)
        return !p.getBoolean(KEY_OPTED_OUT, false) && p.getInt(KEY_AUTO_FAILS, 0) < MAX_AUTO_FAILS
    }
    fun setAutoOptedOut(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_OPTED_OUT, value).apply()
    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

    /**
     * null — hali so'ralmagan. Foydalanuvchi Linux fayl tizimini yuklashga ochiq rozilik berganmi
     * (Wi-Fi'da ham, mobil internetda ham). Roziliksiz avtomatik yuklash bo'lmaydi.
     */
    fun installConsent(context: Context): Boolean? =
        prefs(context).let { if (it.contains(KEY_CONSENT)) it.getBoolean(KEY_CONSENT, false) else null }
    fun setInstallConsent(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_CONSENT, value).apply()

    fun onInstallSucceeded(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putBoolean(KEY_OPTED_OUT, false)
            .putInt(KEY_AUTO_FAILS, 0)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    /** `unsupported` — qurilma proot'ni umuman ishlata olmaydi: qayta urinishning foydasi yo'q. */
    fun onInstallFailed(context: Context, message: String, auto: Boolean, unsupported: Boolean) {
        val p = prefs(context)
        val e = p.edit().putString(KEY_LAST_ERROR, message)
        if (auto) e.putInt(KEY_AUTO_FAILS, p.getInt(KEY_AUTO_FAILS, 0) + if (unsupported) MAX_AUTO_FAILS else 1)
        e.apply()
    }

    /**
     * Fayl asboblari (write_file/read_file/...) uchun yo'l xaritasi. Linux faol bo'lsa, Linux
     * yo'li (`/root/app.py`, `/tmp/x`, `~/a`, nisbiy `a.txt`) rootfs ichidagi haqiqiy fayl bo'ladi —
     * shell `/root/app.py` deb ko'rgan fayl bilan aynan bir xil. `/sdcard` va `/storage` esa
     * telefon xotirasining o'zi (null qaytadi: chaqiruvchi yo'lni o'zgartirmaydi).
     * Linux faol bo'lmasa ham null.
     */
    fun mapGuestPath(context: Context, path: String): File? {
        if (!isActive(context)) return null
        val p = path.trim()
        if (p == "/sdcard" || p.startsWith("/sdcard/") || p == "/storage" || p.startsWith("/storage/")) return null
        val root = rootfsDir(context)
        // Allaqachon rootfs ichidagi haqiqiy (host) yo'l bo'lsa — tegmaymiz.
        if (p.startsWith(root.absolutePath)) return null
        val guest = when {
            p == "~" -> "/root"
            p.startsWith("~/") -> "/root/" + p.removePrefix("~/")
            p.startsWith("/") -> p
            else -> "/root/$p"
        }
        // ".." bilan rootfs'dan tashqariga chiqib ketmasligi uchun: ildizdan normallashtiramiz.
        val clean = java.nio.file.Paths.get("/" + guest.removePrefix("/")).normalize().toString()
        return File(root, clean.removePrefix("/"))
    }

    /** Foydalanuvchi/model ko'radigan yo'l: rootfs ichidagi fayl `/root/x` bo'lib, boshqalari o'z holicha. */
    fun guestPath(context: Context?, f: File): String {
        if (context == null) return f.path
        val rootPath = rootfsDir(context).absolutePath
        val abs = f.absolutePath
        return if (abs == rootPath) "/" else if (abs.startsWith("$rootPath/")) abs.removePrefix(rootPath) else f.path
    }

    /** Agent buyruqlari Linux ichida bajariladimi? Hammasi shart: o'rnatilgan + yoqilgan + proot bor. */
    fun isActive(context: Context): Boolean =
        isEnabled(context) && isInstalled(context) && isRuntimeAvailable(context)

    /**
     * proot `libtalloc.so.2` nomini qidiradi, jniLibs'da esa fayl `libtalloc.so` deb
     * turadi. LD_LIBRARY_PATH ko'rsatadigan papkada kerakli nomli symlink yaratamiz.
     * nativeLibraryDir ilovani yangilaganda o'zgarishi mumkin — shuning uchun har safar tekshiriladi.
     */
    private fun prepareSupport(context: Context): File {
        val dir = supportDir(context).apply { mkdirs() }
        // linker nomi -> jniLibs dagi haqiqiy fayl. libandroid-shmem yangi Termux proot'lari uchun
        // kerak; LD_LIBRARY_PATH faqat shu papkani ko'radi, shuning uchun uni ham bog'laymiz.
        val links = listOf(
            "libtalloc.so.2" to "libtalloc.so",
            "libandroid-shmem.so" to "libandroid-shmem.so",
        )
        for ((linkName, libName) in links) {
            val src = nativeLib(context, libName)
            if (!src.exists()) continue
            val target = src.absolutePath
            val link = File(dir, linkName)
            val current = try { Os.readlink(link.path) } catch (_: Exception) { null }
            if (current != target) {
                link.delete()
                Os.symlink(target, link.path)
            }
        }
        return dir
    }

    /**
     * `proot ... <guestCmd>` ni ishga tushiruvchi ProcessBuilder.
     *
     * Android muhit o'zgaruvchilari (LD_LIBRARY_PATH, ANDROID_ROOT, BOOTCLASSPATH...) Ubuntu
     * ichidagi glibc dasturlarni buzadi, shuning uchun muhit tozalanadi va Ubuntu ichida
     * `env -i` bilan toza muhit beriladi.
     */
    fun processBuilder(context: Context, guestCmd: List<String>): ProcessBuilder {
        val support = prepareSupport(context)

        val cmd = mutableListOf(
            nativeLib(context, "libproot.so").absolutePath,
            "-0",                  // soxta root (apt/dpkg uchun)
            "--link2symlink",      // dpkg hard-link'lari (Android'da hard-link cheklangan)
            "--kill-on-exit",
            "-r", rootfsDir(context).absolutePath,
            "-w", "/root",
            "-b", "/dev", "-b", "/proc", "-b", "/sys"
        )
        cmd += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=$GUEST_PATH",
            "TERM=dumb",
            "LANG=C.UTF-8",
            "TMPDIR=/tmp",
            "DEBIAN_FRONTEND=noninteractive"
        )
        cmd += guestCmd

        val pb = ProcessBuilder(cmd).directory(context.filesDir)
        applyProotEnv(context, pb, support)
        return pb
    }

    private fun applyProotEnv(context: Context, pb: ProcessBuilder, support: File) {
        val env = pb.environment()
        env.clear()
        env["LD_LIBRARY_PATH"] = support.absolutePath
        env["PROOT_LOADER"] = nativeLib(context, "libproot-loader.so").absolutePath
        env["PROOT_TMP_DIR"] = File(context.cacheDir, "proot-tmp").apply { mkdirs() }.absolutePath
        // Ba'zi Android versiyalarida proot'ning seccomp tezlatgichi jarayonni o'ldiradi
        // (UserLAnd ham shuni o'chiradi). Sekinroq, lekin ishonchliroq.
        env["PROOT_NO_SECCOMP"] = "1"
    }

    /**
     * Rootfs yuklashdan OLDIN: proot shu telefonda ishlay oladimi (loader + ptrace)?
     * Android'ning o'z /system/bin/sh'ini proot orqali ishga tushiradi (`-r /`).
     * Muvaffaqiyatsiz bo'lsa, 30 MB behuda yuklanmaydi. Bloklaydi — fon tredda chaqiring.
     */
    fun preflight(context: Context): ExecutionResult {
        if (!isRuntimeAvailable(context)) return ExecutionResult(-1, "proot APK ichida yo'q")
        return try {
            val support = prepareSupport(context)
            val pb = ProcessBuilder(
                nativeLib(context, "libproot.so").absolutePath,
                "-r", "/", "-w", "/", "/system/bin/sh", "-c", "echo proot-ok"
            ).redirectErrorStream(true)
            applyProotEnv(context, pb, support)
            val p = pb.start()
            val out = StringBuffer()
            val reader = Thread {
                try { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } } catch (_: Exception) {}
            }.apply { start() }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                reader.join(1000)
                return ExecutionResult(-1, out.toString().trim() + "\n[vaqt tugadi]")
            }
            reader.join(1000)
            val text = out.toString().trim()
            val code = p.exitValue()
            ExecutionResult(if (code == 0 && text.contains("proot-ok")) 0 else if (code != 0) code else 1, text)
        } catch (e: Exception) {
            ExecutionResult(-1, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Bir martalik buyruq (sinov uchun): chiqishni yig'adi, vaqt tugasa jarayonni to'xtatadi. */
    suspend fun runOnce(context: Context, guestCmd: List<String>, timeoutSec: Long = 90): ExecutionResult =
        withContext(Dispatchers.IO) {
            try {
                val p = processBuilder(context, guestCmd).redirectErrorStream(true).start()
                val sb = StringBuffer()
                val reader = Thread {
                    try {
                        p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') }
                    } catch (_: Exception) {
                    }
                }.apply { start() }
                if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    reader.join(2000)
                    return@withContext ExecutionResult(-1, sb.toString().trim() + "\n[vaqt tugadi: ${timeoutSec}s]")
                }
                reader.join(2000)
                ExecutionResult(p.exitValue(), sb.toString().trim())
            } catch (e: Exception) {
                ExecutionResult(-1, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

    /**
     * Prototipning asosiy sinovi: proot jniLibs'dan ishga tushyaptimi, Ubuntu ichida
     * echo va apt ishlayaptimi.
     */
    suspend fun selfTest(context: Context): ExecutionResult {
        if (!isRuntimeAvailable(context)) {
            return ExecutionResult(-1, "proot APK ichida yo'q (jniLibs/arm64-v8a/libproot.so). CI skripti ishlamagan.")
        }
        if (!isInstalled(context)) return ExecutionResult(-1, "Ubuntu hali o'rnatilmagan.")
        return runOnce(
            context,
            listOf(
                "/bin/sh", "-c",
                "echo hello-from-linux; . /etc/os-release && echo \"\$PRETTY_NAME\"; uname -m; apt --version | head -n 1"
            )
        )
    }

    /** Linux muhitini butunlay o'chiradi. Symlink'larga ergashmaydi (rootfs ichidagi /sdcard havolalari xavfsiz). */
    suspend fun uninstall(context: Context) = withContext(Dispatchers.IO) {
        setEnabled(context, false)
        deleteTreeNoFollow(baseDir(context))
    }

    /** Foydalanuvchi o'chirdi: avtomatik o'rnatish uni qayta o'rnatmasligi uchun belgilanadi. */
    suspend fun removeByUser(context: Context) {
        setAutoOptedOut(context, true)
        uninstall(context)
    }

    /** Bekor qilingan/yarim qolgan o'rnatishdan keyin rootfs'ni tozalash (suspend emas — bekor qilingan coroutine'dan ham ishlaydi). */
    fun wipeRootfsBlocking(context: Context) = deleteTreeNoFollow(rootfsDir(context))

    private fun deleteTreeNoFollow(root: File) {
        if (!root.exists() && !isSymlink(root)) return
        java.nio.file.Files.walkFileTree(root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                java.nio.file.Files.deleteIfExists(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                try { Os.chmod(dir.toString(), 0b111_000_000) } catch (_: Exception) {}
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                java.nio.file.Files.deleteIfExists(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun isSymlink(f: File) = java.nio.file.Files.isSymbolicLink(f.toPath())
}
