package com.mobagent.app.linux

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobagent.app.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * Foydalanuvchi uchun Ubuntu terminali (chatdagi terminal tugmasi ochadigan ekran).
 *
 * Jarayon darajasida yashaydi: ekran yopilsa ham chiqish va joriy papka saqlanadi, uzoq
 * buyruq (masalan `apt install`) davom etaveradi. Agentning shell sessiyasidan ALOHIDA
 * jarayon: `cd`/`export` agentga o'tmaydi, lekin Ubuntu fayllari umumiy.
 *
 * Termux'ga o'xshashlik uchun: ANSI ranglar saqlanadi (`ls`/`grep` rangli), Tab bilan
 * to'ldirish ([complete]), buyruqlar tarixi ([history]), COLUMNS ekran kengligiga moslanadi.
 *
 * Interaktiv to'liq ekranli dasturlar (nano, vim, top) ishlamaydi: PTY yo'q, stdin `/dev/null`.
 */
object LinuxTerminal {
    enum class Kind { CMD, OUT, INFO }

    /** [dir] faqat CMD qatorlari uchun: buyruq kiritilgan paytdagi papka (prompt uchun). */
    data class Line(val text: String, val kind: Kind, val dir: String = "")

    /** Yozuvchi — faqat bitta fon oqimi (`submit` ichidagi corutina); UI faqat o'qiydi. */
    val lines = mutableStateListOf<Line>()
    var running by mutableStateOf(false)
        private set
    var cwd by mutableStateOf("/root")
        private set

    /**
     * `nano fayl` yozilganda ochiladigan o'rnatilgan muharrirning Linux yo'li ("" — yangi bufer,
     * null — muharrir yopiq). UI NanoEditor'ni shu holatga qarab chizadi.
     */
    var editorPath by mutableStateOf<String?>(null)

    /** Buyruqlar tarixi (eng oxirgisi ro'yxat oxirida) — ↑/↓ tugmalari uchun. */
    val history = mutableListOf<String>()

    /** Terminal matn o'lchami (sp). UI'dagi A−/A+ tugmalari o'zgartiradi. */
    var fontSp by mutableStateOf(12)

    /** Ekran kengligi (belgilarda): `ls` ustunlari va boshqa dasturlar shunga moslashadi. */
    @Volatile
    var columns: Int = 80

    private const val MAX_LINES = 3000
    private const val DROP_LINES = 500
    private const val MAX_LINE_CHARS = 4000
    private const val MAX_HISTORY = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // SGR (rang, ESC[...m) QOLADI; kursor harakati, tozalash va OSC kabi boshqa ketma-ketliklar olib tashlanadi.
    private val nonSgr = Regex("\u001B\\[[0-9;?]*[ -/]*[@-ln-~]")
    private val osc = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val charset = Regex("\u001B[()][A-Za-z0-9]")

    private val lock = Any()
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    @Volatile
    private var stopRequested = false

    /**
     * Sessiya ochilganda bir marta yuboriladi. `ls`/`grep`/`diff` faqat quvurga (pipe) ulanmagan
     * holda rangli chiqaradi: shell funksiyasi ichida `$BASHPID == $$` bo'lsa va stdout pipe bo'lsa
     * (ya'ni `ls | wc` yoki `ls > fayl` emas) `--color=always` qo'shiladi. Shuning uchun
     * `ls | grep x` kabi zanjirlar ranglar bilan buzilmaydi.
     */
    private val INIT_SCRIPT = """
export TERM=xterm-256color COLORTERM=truecolor
__mc() { [ "${'$'}BASHPID" = "${'$'}${'$'}" ] && [ -p /dev/stdout ]; }
ls() { if __mc; then command ls -C --color=always "${'$'}@"; else command ls "${'$'}@"; fi; }
dir() { ls "${'$'}@"; }
ll() { ls -alF "${'$'}@"; }
la() { ls -A "${'$'}@"; }
l() { ls -CF "${'$'}@"; }
grep() { if __mc; then command grep --color=always "${'$'}@"; else command grep "${'$'}@"; fi; }
egrep() { grep -E "${'$'}@"; }
fgrep() { grep -F "${'$'}@"; }
diff() { if __mc; then command diff --color=always "${'$'}@"; else command diff "${'$'}@"; fi; }
""".trimIndent() + "\n"

    private fun add(text: String, kind: Kind, dir: String = "") {
        lines.add(Line(text, kind, dir))
        if (lines.size > MAX_LINES) repeat(DROP_LINES) { lines.removeAt(0) }
    }

    private fun clean(raw: String): String =
        raw.replace(osc, "").replace(charset, "").replace(nonSgr, "")
            .replace("\u0007", "")
            .trimEnd('\r').substringAfterLast('\r').take(MAX_LINE_CHARS)

    /** Faqat buyruq bajarilmayotgan paytda tozalaydi (yozuvchi oqim bilan to'qnashmasligi uchun). */
    fun clear() {
        if (!running) lines.clear()
    }

    /** Terminalga oddiy chiqish qatori qo'shadi (masalan Tab variantlari). */
    fun note(text: String) = add(text, Kind.OUT)

    /** Ctrl+C: yozilgan, lekin yuborilmagan buyruqni `^C` bilan bekor qiladi (Termux kabi). */
    fun cancelInput(current: String) = add("$current^C", Kind.CMD, cwd)

    /** Ishlayotgan buyruqni to'xtatadi: shell jarayoni o'ldiriladi, keyingi buyruqda yangisi ochiladi. */
    fun stop() {
        if (!running) return
        stopRequested = true
        process?.destroyForcibly()
    }

    /** Nisbiy yo'lni joriy papka bo'yicha mutlaq Linux yo'liga aylantiradi (`~`, `..` ni hisobga oladi). */
    fun resolvePath(p: String): String {
        val raw = p.trim()
        val abs = when {
            raw == "~" -> "/root"
            raw.startsWith("~/") -> "/root/" + raw.removePrefix("~/")
            raw.startsWith("/") -> raw
            else -> cwd.trimEnd('/') + "/" + raw
        }
        return java.nio.file.Paths.get(abs).normalize().toString().ifEmpty { "/" }
    }

    private val nanoCmd = Regex("^\\s*(?:sudo\\s+)?(?:nano|pico)(?:\\s+(.*))?$")
    private val argRe = Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)")

    /** `nano [-opsiyalar] [fayl]` bo'lsa fayl yo'lini ("" — fayl ko'rsatilmagan), aks holda null qaytaradi. */
    private fun parseNano(cmd: String): String? {
        val m = nanoCmd.matchEntire(cmd.trim()) ?: return null
        val args = argRe.findAll(m.groupValues[1]).map { a ->
            a.groups[1]?.value ?: a.groups[2]?.value ?: a.groups[3]!!.value
        }.toList()
        val file = args.lastOrNull { !it.startsWith("-") && !it.startsWith("+") } ?: return ""
        return resolvePath(file)
    }

    fun submit(context: Context, command: String) {
        val cmd = command.trim('\n', '\r')
        if (cmd.isBlank() || running) return
        val app = context.applicationContext
        running = true
        stopRequested = false
        add(cmd, Kind.CMD, cwd)
        if (history.lastOrNull() != cmd) {
            history.add(cmd)
            if (history.size > MAX_HISTORY) history.removeAt(0)
        }
        // `nano` — o'rnatilgan muharrir (haqiqiy nano PTY talab qiladi, u yerda ishlamaydi).
        parseNano(cmd)?.let { path ->
            if (LinuxEnvironment.isActive(app)) {
                editorPath = path
            } else {
                add(tr("Linux muhiti yoqilmagan yoki o'rnatilmagan. Sozlamalar > Linux muhiti bo'limidan yoqing."), Kind.INFO)
            }
            running = false
            return
        }
        scope.launch {
            try {
                synchronized(lock) { exec(app, cmd) { text, kind -> add(text, kind) } }
            } catch (e: Exception) {
                add("${e.javaClass.simpleName}: ${e.message}", Kind.INFO)
                synchronized(lock) { reset() }
            } finally {
                running = false
            }
        }
    }

    /**
     * Tab bilan to'ldirish: birinchi so'z — buyruq nomlari, qolganlari — fayl/papka yo'llari
     * (papkalar `/` bilan tugaydi). Buyruq ishlayotgan paytda bo'sh ro'yxat qaytaradi.
     */
    suspend fun complete(context: Context, beforeCursor: String): List<String> = withContext(Dispatchers.IO) {
        if (running || !LinuxEnvironment.isActive(context)) return@withContext emptyList()
        val word = beforeCursor.substringAfterLast(' ')
        val first = !beforeCursor.contains(' ')
        val q = "'" + word.replace("'", "'\\''") + "'"
        val script = if (first && !word.contains('/')) {
            "compgen -c -- $q | command grep -v '^__' | sort -u | head -200"
        } else {
            "compgen -f -- $q | head -200 | while IFS= read -r f; do if [ -d \"\$f\" ]; then echo \"\$f/\"; else echo \"\$f\"; fi; done"
        }
        val out = mutableListOf<String>()
        try {
            synchronized(lock) {
                if (running) return@synchronized
                exec(context.applicationContext, script) { text, kind -> if (kind == Kind.OUT) out.add(text.trim()) }
            }
        } catch (_: Exception) {
        }
        out.filter { it.isNotEmpty() }
    }

    private fun reset() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        process?.destroyForcibly()
        process = null
        writer = null
        reader = null
    }

    private fun ensureAlive(context: Context) {
        val p = process
        if (p != null && p.isAlive) return
        reset()
        val started = LinuxEnvironment.processBuilder(context, listOf("/bin/bash"))
            .redirectErrorStream(true)
            .start()
        process = started
        val w = OutputStreamWriter(started.outputStream)
        writer = w
        reader = BufferedReader(InputStreamReader(started.inputStream))
        try {
            w.write(INIT_SCRIPT)
            // Sessiya qayta ochilsa (Ctrl+C, exit) oxirgi papkada davom etamiz — Termux'dagi kabi.
            if (cwd != "/root") w.write("cd '" + cwd.replace("'", "'\\''") + "' 2>/dev/null\n")
            w.flush()
        } catch (_: IOException) {
        }
    }

    private fun exec(context: Context, cmd: String, sink: (String, Kind) -> Unit) {
        if (!LinuxEnvironment.isActive(context)) {
            sink(tr("Linux muhiti yoqilmagan yoki o'rnatilmagan. Sozlamalar > Linux muhiti bo'limidan yoqing."), Kind.INFO)
            return
        }
        ensureAlive(context)

        // `eval '<buyruq>'`: tirnoqi yopilmagan yoki xato buyruq shell'ni "ko'pqatorli kirish"
        // holatida osib qo'ymaydi, xatoni esa o'zi qaytaradi. eval joriy shell'da ishlagani uchun
        // cd/export keyingi buyruqqa o'tadi. Chegara belgisi oxirgi qatorda chiqish kodi va
        // joriy papkani olib keladi.
        val marker = "__MOBAGENT_TERM_${UUID.randomUUID()}__"
        val quoted = "'" + cmd.replace("'", "'\\''") + "'"
        val script = "export COLUMNS=$columns\n" +
            "eval $quoted </dev/null 2>&1\nprintf '%s %s %s\\n' $marker \"\$?\" \"\$PWD\"\n"

        try {
            writer!!.write(script)
            writer!!.flush()
        } catch (e: IOException) {
            reset()
            sink(tr("[terminal sessiyasi yopildi]"), Kind.INFO)
            return
        }

        val r = reader!!
        while (true) {
            val raw = try { r.readLine() } catch (_: IOException) { null }
            if (raw == null) {
                // Jarayon tugadi: `exit`, to'xtatish yoki o'ldirilgan.
                val stopped = stopRequested
                reset()
                sink(if (stopped) "^C" else tr("[terminal sessiyasi yopildi]"), Kind.INFO)
                return
            }
            val idx = raw.indexOf(marker)
            if (idx >= 0) {
                // Oxirgi qator yangi qatorsiz tugagan bo'lsa, chegara belgisi shu qatorga yopishadi.
                val before = clean(raw.substring(0, idx))
                if (before.isNotEmpty()) sink(before, Kind.OUT)
                val rest = raw.substring(idx + marker.length).trim().split(' ', limit = 2)
                rest.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { cwd = it }
                val code = rest.getOrNull(0)?.toIntOrNull()
                if (code != null && code != 0) sink("[exit $code]", Kind.INFO)
                return
            }
            sink(clean(raw), Kind.OUT)
        }
    }
}
