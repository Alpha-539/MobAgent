package com.mobagent.app.agent

import android.content.Context
import com.mobagent.app.linux.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * Doimiy interaktiv shell sessiyasi — Termux/SSH/tmux'dagi kabi, `cd`,
 * `export FOO=bar`, shell funksiyalari va muhit o'zgaruvchilari BIR
 * jarayon ichida saqlanadi va KEYINGI buyruqqa ham o'tadi.
 *
 * ESKI xatti-harakat (CommandExecutor'ning avvalgi runShell'i): har
 * buyruq uchun yangi `ProcessBuilder("sh","-c",cmd)` ishga tushirilardi.
 * Bu degani — har chaqiruv butunlay YANGI jarayon, ya'ni YANGI ishchi
 * katalog va YANGI muhit bilan boshlanardi. Shuning uchun model
 * "cd /sdcard/Download" desa, keyingi buyruq baribir eski katalogda
 * ishga tushardi — xuddi har safar terminalni yopib qayta ochgandek.
 *
 * YANGI: bu klass BITTA "/system/bin/sh" jarayonini butun sessiya
 * davomida ochiq ushlab turadi. Buyruq shu jarayonning stdin'iga
 * yuboriladi, natija esa noyob "sentinel" marker orqali stdout oqimidan
 * ajratib olinadi (marker chiqqunча o'qish davom etadi, undan keyingi
 * son — chiqish kodi). `cd`/`export` xuddi shu jarayonning o'zida
 * bajarilgani uchun, ularning ta'siri avtomatik ravishda keyingi run()
 * chaqiruviga ham o'tadi — qo'shimcha holat kuzatish/regex kerak emas,
 * bu xuddi haqiqiy terminal ishlagandek tabiiy natija.
 */
class PersistentShellSession(
    private val workingDir: File,
    private val context: Context? = null
) {
    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private var stdout: BufferedReader? = null
    private val mutex = Mutex()

    /** Hozirgi jarayon Linux (proot) ichida ishlayaptimi — ensureAlive() belgilaydi. */
    var isLinux: Boolean = false
        private set

    private fun applyEnv(pb: ProcessBuilder) {
        if (context == null) return
        val env = pb.environment()
        ToyboxProvisioner.ensureInstalled(context)
        ToyboxProvisioner.pathPrefix(context)?.let { toyboxBin ->
            // Termux'dagi kabi: bizning applet'larimiz tizimning
            // cheklangan /system/bin dan OLDIN qidiriladi.
            env["PATH"] = "$toyboxBin:${env["PATH"] ?: "/system/bin:/system/xbin"}"
            env["HOME"] = ToyboxProvisioner.homeDir(context).absolutePath
            env["TMPDIR"] = context.cacheDir.absolutePath
        }
    }

    /** Jarayon o'lik bo'lsa (yoki hali ochilmagan bo'lsa), uni (qayta) ishga tushiradi. */
    private fun ensureAlive() {
        val p = process
        if (p != null && p.isAlive) return

        val linux = context != null && LinuxEnvironment.isActive(context)
        isLinux = linux
        val pb = if (linux) {
            // Ubuntu ichidagi bash. stdin — buyruqlar quvuri, shuning uchun har buyruq
            // run() ichida `</dev/null` bilan o'raladi.
            LinuxEnvironment.processBuilder(context!!, listOf("/bin/bash")).redirectErrorStream(true)
        } else {
            ProcessBuilder("/system/bin/sh")
                .directory(workingDir)
                .redirectErrorStream(true)
                .also { applyEnv(it) }
        }

        val started = pb.start()
        process = started
        stdin = OutputStreamWriter(started.outputStream)
        stdout = BufferedReader(InputStreamReader(started.inputStream))
    }

    /**
     * Buyruqni joriy (davom etayotgan) sessiya ichida bajaradi.
     * `cd`/`export` kabi holat o'zgarishlari shu sessiyaning o'zida
     * qoladi va keyingi chaqiruvga avtomatik ko'chadi.
     *
     * Bir vaqtda faqat bitta buyruq bajariladi (mutex) — xuddi haqiqiy
     * terminalda ketma-ket buyruq kiritgandek.
     */
    suspend fun run(command: String, timeoutMs: Long? = null): ExecutionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                ensureAlive()
                // apt/pip kabi buyruqlar uzoq ishlaydi: Linux rejimida vaqt chegarasi kattaroq.
                val limitMs = timeoutMs ?: if (isLinux) 600_000L else 120_000L
                val marker = "__MOBAGENT_DONE_${UUID.randomUUID()}__"
                val writer = stdin
                val reader = stdout
                if (writer == null || reader == null) {
                    return@withLock ExecutionResult(-1, "Terminal sessiyasi ochilmadi")
                }

                if (isLinux) {
                    // stdin'ni o'qiydigan buyruq (masalan interaktiv so'rov) keyingi qatorlarni,
                    // jumladan chegara belgisini yeb qo'ymasligi uchun. cd/export baribir saqlanadi.
                    writer.write("{\n")
                    writer.write(command)
                    writer.write("\n} </dev/null\n")
                } else {
                    writer.write(command)
                    writer.write("\n")
                }
                // Buyruqdan keyin darhol chegara belgisini va $? (chiqish kodi) ni chiqaramiz.
                writer.write("echo \"$marker \$?\"\n")
                writer.flush()

                val out = StringBuilder()
                var exitCode = -1
                val deadline = System.currentTimeMillis() + limitMs

                while (true) {
                    if (!reader.ready()) {
                        if (process?.isAlive != true) {
                            out.append("\n[sessiya kutilmaganda tugadi — keyingi buyruq yangi sessiyada boshlanadi]")
                            reset()
                            break
                        }
                        if (System.currentTimeMillis() > deadline) {
                            out.append("\n[vaqt tugadi: ${limitMs / 1000}s]")
                            break
                        }
                        delay(15)
                        continue
                    }
                    val line = reader.readLine() ?: break
                    if (line.startsWith(marker)) {
                        exitCode = line.removePrefix(marker).trim().toIntOrNull() ?: -1
                        break
                    }
                    if (out.isNotEmpty()) out.append("\n")
                    out.append(line)
                }
                ExecutionResult(exitCode = exitCode, output = out.toString())
            } catch (e: Exception) {
                ExecutionResult(-1, e.localizedMessage ?: "Noma'lum xatolik")
            }
        }
    }

    /** Sessiyani majburan qayta boshlaydi (masalan foydalanuvchi "terminalni qayta ishga tushir" desa). */
    fun reset() {
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
        stdin = null
        stdout = null
    }
}
