package com.mobagent.app.agent

import com.mobagent.app.history.Block
import com.mobagent.app.history.DiffKind
import com.mobagent.app.history.DiffLine
import android.content.Context
import java.io.File

/**
 * Buyruq fayl yozayotgan bo'lsa (`> f`, `>> f`, `tee f`, `sed -i ... f`, `cp/mv ... f`),
 * bajarilishidan OLDIN fayl holatini eslab qoladi, keyin solishtirib
 * VS Code/Copilot'dagidek "qaysi qator qo'shildi / o'chirildi" diff'ini yasaydi.
 */
object FileDiff {
    private const val MAX_BYTES = 512 * 1024
    private const val MAX_LINES = 2000
    private const val CONTEXT = 2

    // Buyruqdagi yozish nishonlari. Oddiy, lekin amalda yetarli; murakkab
    // holatlar (o'zgaruvchi, cd + nisbiy yo'l) topilmasa diff ko'rsatilmaydi.
    private val REDIRECT = Regex("""(?<![0-9&<])>>?\s*(?:"([^"]+)"|'([^']+)'|([^\s;&|<>]+))""")
    private val TEE = Regex("""\btee\s+(?:-a\s+)?(?:"([^"]+)"|'([^']+)'|([^\s;&|<>]+))""")
    private val SED_I = Regex("""\bsed\s+(?:-[a-zA-Z]*i[a-zA-Z]*\S*\s+).*?(?:"([^"]+)"|'([^']+)'|([^\s;&|<>'"]+))\s*(?:$|[;&|])""")

    fun resolve(path: String, workingDir: File, context: Context? = null): File {
        val p = normalizeStoragePaths(path.trim())
        // Linux faol bo'lsa: Linux yo'llari rootfs ichiga, /sdcard esa telefon xotirasiga boradi.
        if (context != null) com.mobagent.app.linux.LinuxEnvironment.mapGuestPath(context, p)?.let { return it }
        return when {
            p.startsWith("/") -> File(p)
            p.startsWith("~/") -> File(workingDir, p.removePrefix("~/"))
            else -> File(workingDir, p)
        }
    }

    fun targetPaths(command: String, workingDir: File, context: Context? = null): List<File> {
        val found = linkedSetOf<String>()
        for (rx in listOf(REDIRECT, TEE, SED_I)) {
            rx.findAll(command).forEach { m ->
                val p = (1..3).firstNotNullOfOrNull { m.groupValues[it].ifBlank { null } }
                if (p != null && !p.startsWith("/dev/") && !p.startsWith("&")) found += p
            }
        }
        return found.map { resolve(it, workingDir, context) }.distinctBy { it.absolutePath }
    }

    /** Bajarishdan oldingi holat: fayl -> eski matn (fayl yo'q bo'lsa null = yangi fayl). */
    fun snapshot(files: List<File>): Map<File, String?> =
        files.mapNotNull { f ->
            when {
                f.exists() && !f.isFile -> null
                f.isFile && f.length() > MAX_BYTES -> null // juda katta: diff ko'rsatilmaydi
                else -> f to readTextOrNull(f)
            }
        }.toMap()

    /** Tool-call turiga qarab qaysi fayllar o'zgarishini aniqlab, oldingi holatni oladi. */
    fun snapshotFor(call: ToolCall, workingDir: File, context: Context? = null): Map<File, String?> = when (call.action) {
        "write_file", "edit_file" -> snapshot(listOfNotNull(call.path?.let { resolve(it, workingDir, context) }))
        else -> snapshot(targetPaths(call.cmd.orEmpty(), workingDir, context))
    }

    /** Bajarilgandan keyin har bir o'zgargan fayl uchun Diff bloki (+ eski nusxani zaxiraga yozadi). */
    fun diffAgainst(before: Map<File, String?>, store: DiffBackupStore): List<Block.Diff> =
        before.mapNotNull { (file, old) ->
            val new = readTextOrNull(file) ?: return@mapNotNull null
            if (old == new) return@mapNotNull null
            val id = java.util.UUID.randomUUID().toString()
            store.save(id, old)
            build(file.path, old, new).copy(id = id)
        }

    private fun readTextOrNull(f: File): String? = try {
        if (f.isFile && f.length() <= MAX_BYTES) f.readText() else null
    } catch (e: Exception) { null }

    fun build(path: String, old: String?, new: String): Block.Diff {
        val oldLines = old?.lines().orEmpty().let { if (old != null && old.endsWith("\n")) it.dropLast(1) else it }
        val newLines = new.lines().let { if (new.endsWith("\n")) it.dropLast(1) else it }
        val raw = if (oldLines.size > MAX_LINES || newLines.size > MAX_LINES) {
            // Juda katta fayl: LCS qimmat, butun faylni "almashtirildi" deb ko'rsatamiz.
            oldLines.mapIndexed { i, t -> DiffLine(DiffKind.REMOVED, t, oldNo = i + 1) } +
                newLines.mapIndexed { i, t -> DiffLine(DiffKind.ADDED, t, newNo = i + 1) }
        } else lcsDiff(oldLines, newLines)
        val added = raw.count { it.kind == DiffKind.ADDED }
        val removed = raw.count { it.kind == DiffKind.REMOVED }
        return Block.Diff(path, collapseContext(raw), added, removed, isNewFile = old == null)
    }

    private fun lcsDiff(a: List<String>, b: List<String>): List<DiffLine> {
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val out = ArrayList<DiffLine>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += DiffLine(DiffKind.CONTEXT, a[i], i + 1, j + 1); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += DiffLine(DiffKind.REMOVED, a[i], oldNo = i + 1); i++ }
                else -> { out += DiffLine(DiffKind.ADDED, b[j], newNo = j + 1); j++ }
            }
        }
        while (i < n) { out += DiffLine(DiffKind.REMOVED, a[i], oldNo = i + 1); i++ }
        while (j < m) { out += DiffLine(DiffKind.ADDED, b[j], newNo = j + 1); j++ }
        return out
    }

    /** O'zgarish atrofidagi CONTEXT qatorlarni qoldirib, qolganini "···" ga yig'adi. */
    private fun collapseContext(lines: List<DiffLine>): List<DiffLine> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { idx, l ->
            if (l.kind != DiffKind.CONTEXT) {
                for (k in (idx - CONTEXT)..(idx + CONTEXT)) if (k in lines.indices) keep[k] = true
            }
        }
        val out = ArrayList<DiffLine>()
        var skipped = 0
        lines.forEachIndexed { idx, l ->
            if (keep[idx]) {
                if (skipped > 0) { out += DiffLine(DiffKind.GAP, "$skipped ta o'zgarmagan qator"); skipped = 0 }
                out += l
            } else skipped++
        }
        if (skipped > 0 && out.isNotEmpty()) out += DiffLine(DiffKind.GAP, "$skipped ta o'zgarmagan qator")
        return out
    }
}

/**
 * Har bir diff'ning ESKI matnini diskda saqlaydi, shunda "Bekor qilish" ilova
 * qayta ochilgandan keyin ham faylni avvalgi holatiga qaytara oladi.
 */
class DiffBackupStore(context: Context) {
    private val dir = File(context.filesDir, "diff_backups").apply { mkdirs() }
    private fun bak(id: String) = File(dir, "$id.old")

    fun save(id: String, old: String?) {
        if (old != null) try { bak(id).writeText(old) } catch (e: Exception) {}
    }

    /** Faylni diff'dan oldingi holatiga qaytaradi. Muvaffaqiyatli bo'lsa true. */
    fun revert(d: Block.Diff): Boolean = try {
        val f = File(d.path)
        val ok = if (d.isNewFile) {
            !f.exists() || f.delete()
        } else {
            val b = bak(d.id)
            if (!b.exists()) false else { f.writeText(b.readText()); true }
        }
        if (ok) drop(d.id)
        ok
    } catch (e: Exception) { false }

    fun drop(id: String) { bak(id).delete() }
}
