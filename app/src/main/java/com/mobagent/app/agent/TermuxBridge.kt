package com.mobagent.app.agent

import android.content.Context
import android.content.Intent
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Termux ilovasi bilan ko'prik: "pkg install", python/node/git/clang kabi
 * to'liq interpretator/kompilyatorlarni ishga tushirish.
 *
 * NIMA UCHUN BU KERAK (va nega buni O'ZIMIZ mustaqil qila olmaymiz):
 * Termux'ning oldindan tayyorlangan paketlari (python, node, git, clang...)
 * ichida ko'plab yo'llar (dynamic linker interpreter yo'li, shebang qatorlari,
 * `sys.prefix` kabi ichki sozlamalar) "/data/data/com.termux/files/usr" ga
 * QATTIQ bog'langan holda kompilyatsiya qilingan. Ularni shunchaki
 * yuklab olib boshqa ilovaning (bizning MobAgent'ning) shaxsiy papkasiga
 * ko'chirib qo'ysak — o'sha yo'l bizning ilovamizda MAVJUD BO'LMAGANI
 * uchun ular umuman ishga tushmaydi (dynamic linker binarysini,
 * python esa o'zining standart kutubxonasini topa olmaydi). Buni to'g'ri
 * hal qilishning yagona real yo'li — bu paketlarni ULARNING O'Z UYI
 * bo'lgan haqiqiy Termux ilovasi ICHIDA ishga tushirish. Shu sabab, agar
 * foydalanuvchida Termux o'rnatilgan bo'lsa, bunday buyruqlar Termux'ning
 * rasman hujjatlashtirilgan RUN_COMMAND xizmatiga yuboriladi.
 *
 * MEXANIZM (fayl orqali natija olish — Termux hujjatlarida rasman
 * tasdiqlangan, eng ishonchli usul):
 *  1) Termux'ga RUN_COMMAND intent yuboriladi: qaysi dastur (masalan
 *     $PREFIX/bin/bash), qanday argumentlar, qaysi papkada ishlashi va
 *     natijani QAYERGA (umumiy, ikkala ilova ham yoza oladigan xotira
 *     papkasiga) fayl sifatida yozishi kerakligi ko'rsatiladi.
 *  2) Termux buyruqni bajaradi va natijani (stdout/stderr/exit_code)
 *     alohida fayllar sifatida o'sha papkaga yozadi.
 *  3) Biz shu fayllar paydo bo'lishini kutamiz (poll), o'qiymiz va
 *     tozalaymiz.
 *
 * SHART-SHAROIT (foydalanuvchi tomonidan bir martalik sozlash):
 *  - Termux o'rnatilgan bo'lishi kerak (F-Droid yoki GitHub Release —
 *    Play Store versiyasi 2021'dan beri arxivlangan va yangi paketlar
 *    bilan mos kelmasligi mumkin).
 *  - Termux ichida bir marta `termux-setup-storage` bajarilgan bo'lishi
 *    kerak (tashqi xotiraga yozish uchun).
 *  - MobAgent'ning o'zida "Barcha fayllarga kirish" ruxsati yoqilgan
 *    bo'lishi kerak (ilova buni allaqachon so'raydi — StoragePermission.kt).
 *  - Android tizim Sozlamalar > Ilovalar > MobAgent > Ruxsatlar sahifasida
 *    "Termux buyruqlarini bajarish" (RUN_COMMAND) ruxsati berilgan bo'lishi
 *    kerak.
 */
object TermuxBridge {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE_CLASS = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    // Quyidagi extra kalitlari Termux'ning rasman hujjatlashtirilgan
    // RUN_COMMAND Intent kontraktidan olingan (termux-app wiki: "RUN_COMMAND
    // Intent"). Bularning "com.termux.RUN_COMMAND_*" shakli ko'plab mustaqil
    // manbalarda (rasmiy `am startservice` misollari, termux-app GitHub
    // issue'lari) bir xilda tasdiqlangan.
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_RESULT_DIRECTORY = "com.termux.RUN_COMMAND_RESULT_DIRECTORY"
    private const val EXTRA_RESULT_SINGLE_FILE = "com.termux.RUN_COMMAND_RESULT_SINGLE_FILE"
    private const val EXTRA_RESULT_FILE_BASENAME = "com.termux.RUN_COMMAND_RESULT_FILE_BASENAME"

    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Termux ham, biz ham yoza oladigan umumiy (tashqi) xotira papkasi. */
    private fun bridgeDir(): File =
        File(Environment.getExternalStorageDirectory(), "MobAgentTermuxBridge").apply { mkdirs() }

    /**
     * Termux ichida ixtiyoriy bosh buyruqni bajaradi (masalan
     * "pkg install -y python", "python3 script.py", "git clone ...").
     * `bash -lc` orqali ishga tushiriladi — shunda Termux'ning o'z
     * login-muhiti (PATH va h.k.) to'g'ri yuklanadi.
     */
    suspend fun runShellCommand(
        context: Context,
        command: String,
        workdir: String = TERMUX_HOME,
        timeoutMs: Long = 5 * 60_000
    ): ExecutionResult = withContext(Dispatchers.IO) {
        if (!isInstalled(context)) {
            return@withContext ExecutionResult(
                -1,
                "Termux ilovasi topilmadi. \"$command\" python/node/git/clang kabi to'liq " +
                    "vositalarni talab qiladi — bularni to'g'ri ishlashi uchun Termux " +
                    "(F-Droid yoki GitHub'dagi rasmiy versiya, Play Store emas) o'rnatilgan " +
                    "bo'lishi kerak."
            )
        }

        val dir = bridgeDir()
        val basename = "mobagent-${UUID.randomUUID()}-"

        try {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE_CLASS)
                action = ACTION_RUN_COMMAND
                putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH)
                putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
                putExtra(EXTRA_WORKDIR, workdir)
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_RESULT_DIRECTORY, dir.absolutePath)
                putExtra(EXTRA_RESULT_SINGLE_FILE, false)
                putExtra(EXTRA_RESULT_FILE_BASENAME, basename)
            }
            context.startForegroundService(intent)
        } catch (e: Exception) {
            return@withContext ExecutionResult(
                -1,
                "Termux'ga buyruq yuborib bo'lmadi: ${e.localizedMessage}\n" +
                    "Ko'pincha sabab: MobAgent'ga tizim Sozlamalar > Ilovalar > MobAgent > " +
                    "Ruxsatlar sahifasida \"Run commands in Termux environment\" ruxsati " +
                    "berilmagan, yoki Termux'ning termux.properties faylida " +
                    "\"allow-external-apps=true\" yoqilmagan (~/.termux/termux.properties)."
            )
        }

        val exitFile = File(dir, "${basename}exit_code")
        val stdoutFile = File(dir, "${basename}stdout")
        val stderrFile = File(dir, "${basename}stderr")
        val errFile = File(dir, "${basename}err")
        val errmsgFile = File(dir, "${basename}errmsg")

        val deadline = System.currentTimeMillis() + timeoutMs
        while (!exitFile.exists() && System.currentTimeMillis() < deadline) {
            delay(200)
        }

        if (!exitFile.exists()) {
            cleanup(dir, basename)
            return@withContext ExecutionResult(
                -1,
                "Termux'dan javob kelmadi (${timeoutMs / 1000}s kutildi). Tekshiring: " +
                    "(1) Termux'da bir marta \"termux-setup-storage\" bajarilganmi, " +
                    "(2) ~/.termux/termux.properties faylida \"allow-external-apps=true\" bormi, " +
                    "(3) MobAgent'ga RUN_COMMAND ruxsati berilganmi."
            )
        }

        val internalErr = errFile.takeIf { it.exists() }?.readText()?.trim()
        if (!internalErr.isNullOrEmpty() && internalErr != "-1") {
            val msg = errmsgFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
            cleanup(dir, basename)
            return@withContext ExecutionResult(-1, "Termux ichki xatosi: $msg")
        }

        val exitCode = exitFile.readText().trim().toIntOrNull() ?: -1
        val stdout = stdoutFile.takeIf { it.exists() }?.readText().orEmpty()
        val stderr = stderrFile.takeIf { it.exists() }?.readText().orEmpty()
        cleanup(dir, basename)

        val combined = buildString {
            append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(stderr)
            }
        }
        ExecutionResult(exitCode = exitCode, output = combined)
    }

    /** Qulaylik uchun: "pkg install -y <paketlar>". */
    suspend fun pkgInstall(context: Context, packages: List<String>): ExecutionResult {
        val list = packages.joinToString(" ")
        // Paket yuklab olish sekin internetda uzoq davom etishi mumkin — vaqt chegarasi kattaroq.
        return runShellCommand(context, "pkg install -y $list", timeoutMs = 10 * 60_000)
    }

    private fun cleanup(dir: File, basename: String) {
        dir.listFiles { f -> f.name.startsWith(basename) }?.forEach { it.delete() }
    }
}
