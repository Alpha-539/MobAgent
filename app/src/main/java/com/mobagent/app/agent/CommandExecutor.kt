package com.mobagent.app.agent

import android.content.Context
import com.mobagent.app.linux.LinuxEnvironment
import java.io.File

data class ExecutionResult(val exitCode: Int, val output: String)

/**
 * Model (ayniqsa kichik, Termux ma'lumotlari bilan o'qitilgan) ko'pincha
 * "~/storage/shared/DCIM/index.html" kabi Termux yo'llarini yozadi. MobAgent'da
 * "~" ilovaning shaxsiy papkasi bo'lib, u yerda storage/ degan papka YO'Q, ustiga
 * qo'shtirnoq ichidagi "~" umuman kengaymaydi — natijada "No such file or
 * directory". Prompt qanchalik aniq yozilmasin, model vaqti-vaqti bilan baribir
 * shunday qiladi, shuning uchun buyruq bajarilishidan OLDIN bu yo'llar telefon
 * xotirasining haqiqiy yo'liga (/sdcard) almashtiriladi.
 */
private val TERMUX_STORAGE_REGEX = Regex(
    "(?:~|\\\$HOME|\\\$\\{HOME\\}|/data/data/com\\.termux/files/home)/storage" +
        "(?:/(downloads|dcim|pictures|music|movies|shared))?(?![A-Za-z0-9_])"
)

fun normalizeStoragePaths(command: String): String =
    TERMUX_STORAGE_REGEX.replace(command) { m ->
        when (m.groupValues[1]) {
            "downloads" -> "/sdcard/Download"
            "dcim" -> "/sdcard/DCIM"
            "pictures" -> "/sdcard/Pictures"
            "music" -> "/sdcard/Music"
            "movies" -> "/sdcard/Movies"
            else -> "/sdcard" // "shared" yoki pastki papkasiz "~/storage"
        }
    }

/**
 * Android'ning o'z ichki qobig'i orqali buyruqlarni bajaradi.
 * Har bir chaqiruvdan oldin UI qatlamida Security Guard Dialog orqali
 * foydalanuvchi tasdig'i olinishi SHART (AgentOrchestrator.kt ga qarang).
 *
 * Ilgari faqat /system/bin/sh'ning o'z (juda cheklangan) toolbox/toybox
 * to'plami mavjud edi — masalan to'liq `grep -P`, `sed`, `tar`, `find`
 * kabi ko'plab buyruqlar ishlamas yoki cheklangan bo'lardi. Endi root
 * talab qilmaydigan, Termux uslubidagi to'liq toybox to'plami ham
 * ulanadi (ToyboxProvisioner.kt) — mavjud bo'lsa PATH'ning boshiga
 * qo'shiladi, mavjud bo'lmasa (masalan qurilma ABI'si uchun
 * hali build qilinmagan bo'lsa) jimgina eski xatti-harakatga qaytadi.
 *
 * DOIMIY SESSIYA: har buyruq endi bitta umumiy PersistentShellSession
 * ichida ishlaydi — "cd", "export FOO=bar" kabi holat o'zgarishlari
 * KEYINGI buyruqqa ham o'tadi (xuddi haqiqiy terminal/SSH sessiyasidagidek).
 * Batafsil: PersistentShellSession.kt.
 *
 * TERMUX YO'NALTIRISH: toybox faqat asosiy UNIX vositalarini beradi —
 * to'liq interpretator (python/node/ruby) yoki kompilyator/paket menejeri
 * (git/clang/pkg) EMAS. Bunday buyruqlar kelsa va qurilmada Termux
 * o'rnatilgan bo'lsa, buyruq avtomatik Termux'ga (TermuxBridge.kt)
 * yo'naltiriladi — sababi va cheklovlari o'sha faylda batafsil yozilgan.
 *
 * QURILMA IMKONIYATLARI (termux-api): batareya holati, kamera surati,
 * clipboard, bildirishnoma kabi buyruqlar ham xuddi shu yo'l bilan
 * Termux'ga yuboriladi — ular alohida "Termux:API" ilovasi orqali
 * ishlaydi (Termux ichida "pkg install termux-api" + Termux:API'ni
 * alohida o'rnatish kerak).
 */
class CommandExecutor(
    private val workingDir: File,
    private val context: Context? = null
) {
    // Ilova (yoki hech bo'lmasa shu ViewModel/ekran) umri davomida saqlanadigan
    // bitta doimiy sessiya. restartSession() chaqirilguncha yashaydi.
    private val session by lazy { PersistentShellSession(workingDir, context) }

    // toybox'da mavjud bo'lmagan, to'liq interpretator/kompilyator/paket
    // menejeri talab qiladigan buyruqlar — bular Termux'ga yo'naltiriladi.
    private val termuxOnlyCommands = setOf(
        "pkg", "apt", "apt-get", "dpkg",
        "python", "python3", "pip", "pip3",
        "node", "npm", "npx", "yarn",
        "ruby", "gem", "bundle",
        "git",
        "clang", "clang++", "gcc", "g++", "cc", "c++", "make", "cmake",
        "java", "javac", "go", "rustc", "cargo", "perl", "php",
        // termux-api (alohida "Termux:API" ilovasi + Termux ichida
        // "pkg install termux-api" talab qiladi) — batareya, kamera,
        // clipboard, bildirishnoma kabi qurilma imkoniyatlariga kirish.
        // Bular ham toybox'da yo'q, Termux:API ilovasi orqali ishlaydi,
        // shuning uchun xuddi python/git kabi shu yerga yo'naltiriladi.
        "termux-battery-status",
        "termux-camera-info", "termux-camera-photo",
        "termux-clipboard-get", "termux-clipboard-set",
        "termux-notification", "termux-notification-remove",
        "termux-vibrate", "termux-toast"
    )

    // Oxirgi buyruq Linux rejimida bajarilganmi: rejim o'zgarsa (foydalanuvchi sozlamada
    // yoqdi/o'chirdi) eski sessiya yopilib, yangisi kerakli muhitda ochiladi.
    private var lastLinuxMode: Boolean? = null

    suspend fun runShell(rawCommand: String): ExecutionResult {
        val command = normalizeStoragePaths(rawCommand)
        val linux = context != null && LinuxEnvironment.isActive(context)
        if (lastLinuxMode != null && lastLinuxMode != linux) session.reset()
        lastLinuxMode = linux

        val firstWord = command.trim().substringBefore(' ')
        // Linux rejimida apt/python/git/gcc Ubuntu ichida o'zi bor — Termux'ga yo'naltirilmaydi.
        val needsTermux = false && !linux && context != null &&
            firstWord in termuxOnlyCommands &&
            ToyboxProvisioner.pathPrefix(context)?.let { File(it, firstWord).exists() } != true

        return if (needsTermux) {
            TermuxBridge.runShellCommand(context!!, command, workdir = "/data/data/com.termux/files/home")
        } else {
            session.run(command)
        }
    }

    /** Fayl asboblari yo'li: Linux faol bo'lsa Linux fayl tizimiga (yoki /sdcard'ga) xaritalanadi. */
    private fun resolve(path: String): File = FileDiff.resolve(path, workingDir, context)

    /** Natija matnida modelga Linux yo'li (`/root/x`) ko'rsatiladi, ilovaning ichki yo'li emas. */
    private fun shown(f: File): String = LinuxEnvironment.guestPath(context, f)

    /** Faylni to'liq yozadi (yo'q bo'lsa yaratadi). Shell'siz — qochirish (escaping) xatolari bo'lmaydi. */
    fun writeFile(path: String, content: String): ExecutionResult = try {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
        ExecutionResult(0, "${shown(f)} yozildi (${content.lines().size} qator)" + validationNote(f, content))
    } catch (e: Exception) {
        ExecutionResult(1, "Xato: ${e.message}")
    }

    /**
     * `find` matnini faylda BIR marta uchrasagina `replace` bilan almashtiradi.
     * Kichik model matnni ko'pincha bo'sh joy/chekinishda adashib yozadi, shuning
     * uchun aniq moslik topilmasa, bo'sh joylarga BEFARQ moslik ham sinaladi
     * (u ham aynan bitta joyni ko'rsatsagina qo'llanadi). Topilmasa, modelga
     * faylning eng o'xshash qatori qaytariladi — u shundan to'g'rilab qayta urinadi.
     */
    fun editFile(path: String, find: String, replace: String): ExecutionResult = try {
        val f = resolve(path)
        when {
            !f.isFile -> ExecutionResult(1, "Xato: fayl topilmadi: ${shown(f)}")
            find.isEmpty() -> ExecutionResult(1, "Xato: find bo'sh. Provide the exact existing text in \"find\".")
            else -> {
                val text = f.readText()
                val exact = indexesOf(text, find)
                val range: IntRange? = when {
                    exact.size == 1 -> exact[0]..(exact[0] + find.length - 1)
                    exact.size > 1 -> null
                    else -> fuzzyUnique(text, find)
                }
                when {
                    exact.size > 1 ->
                        ExecutionResult(1, "Xato: find matni ${exact.size} marta uchraydi. Give a longer, unique piece of text.")
                    range == null && exact.isEmpty() && fuzzyCount(text, find) > 1 ->
                        ExecutionResult(1, "Xato: find matni bir necha joyga mos keladi. Give a longer, unique piece of text.")
                    range == null ->
                        ExecutionResult(1, "Xato: find matni faylda topilmadi." + closestLineHint(text, find) +
                            " Copy the text exactly, or use read_file and retry.")
                    else -> {
                        val out = text.substring(0, range.first) + replace + text.substring(range.last + 1)
                        f.writeText(out)
                        val how = if (exact.size == 1) "" else " (bo'sh joylar farqi e'tiborga olinmadi)"
                        ExecutionResult(0, "${shown(f)} tahrirlandi$how" + validationNote(f, out))
                    }
                }
            }
        }
    } catch (e: Exception) {
        ExecutionResult(1, "Xato: ${e.message}")
    }

    private fun validationNote(f: File, text: String): String =
        FileValidator.validate(f, text)?.let {
            "\nWARNING: the file was saved but has a problem — $it. Fix it with edit_file or write_file."
        }.orEmpty()

    private fun indexesOf(text: String, needle: String): List<Int> {
        val r = mutableListOf<Int>()
        var i = text.indexOf(needle)
        while (i >= 0) { r += i; i = text.indexOf(needle, i + needle.length) }
        return r
    }

    /** Bo'sh joy ketma-ketliklariga befarq regex: "a  b\n c" == "a b c". */
    private fun looseRegex(find: String): Regex? {
        val tokens = find.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return Regex(tokens.joinToString("\\s+") { Regex.escape(it) })
    }

    private fun fuzzyCount(text: String, find: String): Int =
        looseRegex(find)?.findAll(text)?.take(2)?.count() ?: 0

    private fun fuzzyUnique(text: String, find: String): IntRange? {
        val matches = looseRegex(find)?.findAll(text)?.take(2)?.toList() ?: return null
        return if (matches.size == 1) matches[0].range else null
    }

    /** Faylda `find`ning birinchi qatoriga eng o'xshash qatorni (so'z kesishmasi bo'yicha) topadi. */
    private fun closestLineHint(text: String, find: String): String {
        val want = find.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        val wantTokens = want.split(Regex("\\W+")).filter { it.length > 1 }.toSet()
        if (wantTokens.isEmpty()) return ""
        var best: String? = null
        var bestScore = 0.0
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val tokens = t.split(Regex("\\W+")).filter { it.length > 1 }.toSet()
            if (tokens.isEmpty()) continue
            val score = wantTokens.intersect(tokens).size.toDouble() / wantTokens.union(tokens).size
            if (score > bestScore) { bestScore = score; best = t }
        }
        return if (best != null && bestScore >= 0.4) " Closest line in the file: ${best.take(200)}" else ""
    }

    // ---- Oddiy fayl asboblari (shell'siz): kichik modellar bo'sh joylarni
    // (yo'l) to'ldirishi yetarli, bash sintaksisini bilishi shart emas. ----

    /** Papka tarkibi: papkalar `/` bilan, avval papkalar, keyin fayllar (o'lchami bilan). */
    fun listDir(path: String): ExecutionResult = try {
        val d = resolve(path)
        when {
            !d.exists() -> ExecutionResult(1, "Xato: papka topilmadi: ${shown(d)}")
            !d.isDirectory -> ExecutionResult(1, "Xato: bu papka emas: ${shown(d)}. Use read_file for files.")
            else -> {
                // Eng yangi o'zgartirilganlari birinchi: model "oxirgi fayl" so'ralganda ro'yxat boshidan oladi.
                val items = d.listFiles()?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name.lowercase() }).orEmpty()
                if (items.isEmpty()) ExecutionResult(0, "${shown(d)} bo'sh")
                else {
                    val shown = items.take(LIST_LIMIT).joinToString("\n") {
                        val date = DATE_FMT.format(java.util.Date(it.lastModified()))
                        if (it.isDirectory) "${it.name}/  ($date)" else "${it.name}  (${it.length()} bayt, $date)"
                    }
                    val more = if (items.size > LIST_LIMIT) "\n... yana ${items.size - LIST_LIMIT} ta" else ""
                    ExecutionResult(0, "[eng yangi o'zgartirilgan birinchi / newest first]\n" + shown + more)
                }
            }
        }
    } catch (e: Exception) {
        ExecutionResult(1, "Xato: ${e.message}")
    }

    /** Fayl matni (juda uzun bo'lsa kesiladi — kontekstni to'ldirib yubormaslik uchun). */
    fun readFile(path: String): ExecutionResult = try {
        val f = resolve(path)
        when {
            !f.isFile -> ExecutionResult(1, "Xato: fayl topilmadi: ${shown(f)}")
            f.length() > READ_MAX_BYTES * 8 -> ExecutionResult(1, "Xato: fayl juda katta (${f.length()} bayt)")
            else -> {
                val text = f.readText()
                if (text.length <= READ_MAX_CHARS) ExecutionResult(0, text)
                else ExecutionResult(0, text.take(READ_MAX_CHARS) + "\n... [kesildi: jami ${text.length} belgi]")
            }
        }
    } catch (e: Exception) {
        ExecutionResult(1, "Xato: ${e.message}")
    }

    /** Fayl yoki papkani o'chiradi. Telefon xotirasining asosiy papkalarini o'chirishni rad etadi. */
    fun deletePath(path: String): ExecutionResult = try {
        val f = resolve(path)
        when {
            !f.exists() -> ExecutionResult(1, "Xato: topilmadi: ${shown(f)}")
            isProtectedPath(f) -> ExecutionResult(1, "Xato: bu papkani o'chirib bo'lmaydi: ${shown(f)}")
            else -> {
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (ok) ExecutionResult(0, "${shown(f)} o'chirildi") else ExecutionResult(1, "Xato: o'chirib bo'lmadi: ${shown(f)}")
            }
        }
    } catch (e: Exception) {
        ExecutionResult(1, "Xato: ${e.message}")
    }

    /** Nomini o'zgartiradi yoki ko'chiradi. Nishon mavjud bo'lsa ustidan yozmaydi. */
    fun renamePath(path: String, to: String): ExecutionResult = try {
        val src = resolve(path)
        val dst = resolve(to)
        when {
            !src.exists() -> ExecutionResult(1, "Xato: topilmadi: ${shown(src)}")
            isProtectedPath(src) -> ExecutionResult(1, "Xato: bu papkani ko'chirib bo'lmaydi: ${shown(src)}")
            dst.exists() -> ExecutionResult(1, "Xato: nishon allaqachon mavjud: ${shown(dst)}. Choose a different name.")
            else -> {
                dst.parentFile?.mkdirs()
                val ok = src.renameTo(dst) || run {
                    // Turli xotira bo'limlari orasida renameTo ishlamasligi mumkin.
                    src.copyRecursively(dst, overwrite = false) && src.deleteRecursively()
                }
                if (ok) ExecutionResult(0, "${shown(src)} -> ${shown(dst)}") else ExecutionResult(1, "Xato: ko'chirib bo'lmadi")
            }
        }
    } catch (e: Exception) {
        ExecutionResult(1, "Xato: ${e.message}")
    }

    private fun isProtectedPath(f: File): Boolean {
        val p = f.absoluteFile.normalize().path.trimEnd('/')
        if (p.isEmpty()) return true
        // Linux: rootfs ildizi va uning birinchi daraja papkalari (/root, /usr, /etc, /bin...) o'chirilmaydi.
        if (context != null) {
            val g = LinuxEnvironment.guestPath(context, f)
            if (g != f.path && g.trim('/').count { it == '/' } == 0) return true
        }
        if (p == workingDir.absoluteFile.normalize().path.trimEnd('/')) return true
        val roots = listOf("/sdcard", "/storage/emulated/0", "/storage/emulated", "/storage", "/data", "/system", "/sdcard/Android")
        if (p in roots) return true
        val top = setOf("Download", "DCIM", "Documents", "Pictures", "Music", "Movies", "Android")
        return roots.take(2).any { r -> p.removePrefix("$r/").let { it != p && it in top } }
    }

    /** Terminal sessiyasini majburan qayta boshlash (masalan foydalanuvchi so'rasa). */
    fun restartSession() = session.reset()

    private companion object {
        const val LIST_LIMIT = 200
        val DATE_FMT get() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        const val READ_MAX_CHARS = 6000
        const val READ_MAX_BYTES = 64 * 1024
    }
}
