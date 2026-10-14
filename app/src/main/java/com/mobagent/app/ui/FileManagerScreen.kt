package com.mobagent.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.mobagent.app.i18n.mirrorInRtl
import com.mobagent.app.i18n.tr
import com.mobagent.app.linux.LinuxEnvironment
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Fayl menejeri: Linux uyi (/root) va telefon xotirasi (/sdcard) ni ko'rish, faylni ochish / ulashish,
 * nomini o'zgartirish, o'chirish, yangi papka va fayl yaratish, hamda fayllarni Linux <-> telefon
 * xotirasi o'rtasida nusxalash / ko'chirish.
 * Uzoq bosib bir nechta faylni tanlash mumkin (ulashish / o'chirish).
 */
private class FmRoot(val id: String, val label: String, val dir: File, val display: String)

private class FmEntry(
    val file: File,
    val isDir: Boolean,
    val name: String,
    val size: Long,
    val modified: Long,
    val childCount: Int
)

private fun buildRoots(context: Context): List<FmRoot> {
    val list = mutableListOf<FmRoot>()
    if (LinuxEnvironment.isInstalled(context)) {
        val home = File(LinuxEnvironment.rootfsDir(context), "root").apply { mkdirs() }
        list += FmRoot("linux", tr("Linux"), home, "/root")
    }
    list += FmRoot("app", tr("Ilova papkasi"), context.filesDir, "/app")
    return list
}

private fun listDir(dir: File): List<FmEntry> {
    val arr = dir.listFiles() ?: return emptyList()
    return arr.map { f ->
        val d = f.isDirectory
        FmEntry(f, d, f.name, if (d) 0L else f.length(), f.lastModified(), if (d) (f.list()?.size ?: 0) else 0)
    }.sortedWith(compareByDescending<FmEntry> { it.isDir }.thenBy { it.name.lowercase() })
}

private fun fmtSize(b: Long): String = when {
    b < 1024L -> "$b B"
    b < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.2f GB", b / (1024.0 * 1024 * 1024))
}

private val TEXT_EXT = setOf(
    "txt", "md", "log", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "csv",
    "sh", "py", "js", "ts", "kt", "java", "c", "h", "cpp", "rs", "go", "html", "css", "sql"
)
private val CODE_EXT = setOf("sh", "py", "js", "ts", "kt", "java", "c", "h", "cpp", "rs", "go", "html", "css", "sql", "json", "xml", "yaml", "yml")
private val ARCHIVE_EXT = setOf("zip", "tar", "gz", "xz", "bz2", "7z", "rar", "tgz", "apk")

private fun mimeOf(f: File): String {
    val ext = f.extension.lowercase()
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
    return if (ext in TEXT_EXT) "text/plain" else "*/*"
}

private fun iconFor(e: FmEntry): ImageVector {
    if (e.isDir) return Icons.Filled.Folder
    val ext = e.file.extension.lowercase()
    val mime = mimeOf(e.file)
    return when {
        mime.startsWith("image/") -> Icons.Filled.Image
        mime.startsWith("video/") -> Icons.Filled.Movie
        mime.startsWith("audio/") -> Icons.Filled.MusicNote
        ext == "pdf" -> Icons.Filled.PictureAsPdf
        ext in ARCHIVE_EXT -> Icons.Filled.Archive
        ext in CODE_EXT -> Icons.Filled.Code
        else -> Icons.Filled.Description
    }
}

private fun uriFor(context: Context, f: File): Uri? = try {
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
} catch (e: Exception) {
    null
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun openFile(context: Context, f: File) {
    val uri = uriFor(context, f) ?: return toast(context, tr("Amal bajarilmadi"))
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeOf(f))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast(context, tr("Ochadigan ilova topilmadi"))
    } catch (e: Exception) {
        toast(context, tr("Amal bajarilmadi"))
    }
}

private fun shareFiles(context: Context, files: List<File>) {
    val onlyFiles = files.filter { it.isFile }
    if (onlyFiles.isEmpty()) return toast(context, tr("Papkani ulashib bo'lmaydi"))
    val uris = onlyFiles.mapNotNull { uriFor(context, it) }
    if (uris.isEmpty()) return toast(context, tr("Amal bajarilmadi"))

    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(onlyFiles.first())
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    val clip = ClipData.newRawUri("", uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    send.clipData = clip
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    try {
        context.startActivity(Intent.createChooser(send, tr("Ulashish")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(context, tr("Amal bajarilmadi"))
    }
    if (onlyFiles.size < files.size) toast(context, tr("Papkalar o'tkazib yuborildi"))
}

/** Symlink'ni ichiga kirmay, faqat o'zini o'chiradi (Linux rootfs'da mutlaq symlink'lar bor). */
private fun deleteTree(f: File): Boolean {
    if (!java.nio.file.Files.isSymbolicLink(f.toPath()) && f.isDirectory) {
        f.listFiles()?.forEach { deleteTree(it) }
    }
    return f.delete()
}

/** Nishon papka hali tanlanmagan bo'lsa: telefonda Download, Linuxda uy papkasi. */
private fun defaultTransferDir(r: FmRoot): File =
    if (r.id == "phone") File(r.dir, "Download").takeIf { it.isDirectory } ?: r.dir else r.dir

private class TransferResult(var ok: Int = 0, var failed: Int = 0, var skipped: Int = 0)

/** Nom band bo'lsa "fayl (1).txt", "fayl (2).txt" ... — mavjud faylni hech qachon ustidan yozmaydi. */
private fun uniqueDest(dir: File, name: String): File {
    var f = File(dir, name)
    if (!f.exists()) return f
    val dot = name.lastIndexOf('.').takeIf { it > 0 }
    val base = if (dot != null) name.substring(0, dot) else name
    val ext = if (dot != null) name.substring(dot) else ""
    var i = 1
    while (true) {
        f = File(dir, "$base ($i)$ext")
        if (!f.exists()) return f
        i++
    }
}

/**
 * Daraxtni nusxalaydi. Symlink'lar o'tkazib yuboriladi: Linux rootfs'dagi mutlaq symlink'lar
 * telefon xotirasida ma'nosiz, /sdcard esa symlink'ni qo'llamaydi.
 */
private fun copyTree(src: File, dst: File, res: TransferResult): Boolean {
    if (java.nio.file.Files.isSymbolicLink(src.toPath())) { res.skipped++; return true }
    if (src.isDirectory) {
        if (!dst.mkdirs() && !dst.isDirectory) return false
        var ok = true
        src.listFiles()?.forEach { if (!copyTree(it, File(dst, it.name), res)) ok = false }
        return ok
    }
    return try {
        src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Fayllarni [destDir] ichiga nusxalaydi yoki ko'chiradi. Ko'chirishda manba faqat nusxa
 * to'liq va symlink'siz bajarilgandagina o'chiriladi — ma'lumot yo'qolmasligi uchun.
 */
private fun transferFiles(items: List<File>, destDir: File, move: Boolean): TransferResult {
    val res = TransferResult()
    if (!destDir.isDirectory && !destDir.mkdirs()) { res.failed = items.size; return res }
    for (src in items) {
        val dst = uniqueDest(destDir, src.name)
        try {
            val nested = src.isDirectory && dst.canonicalPath.startsWith(src.canonicalPath + "/")
            if (nested) { res.failed++; continue }
            if (move && src.renameTo(dst)) { res.ok++; continue }
            val skippedBefore = res.skipped
            if (copyTree(src, dst, res)) {
                if (move) {
                    if (res.skipped == skippedBefore) deleteTree(src)
                }
                res.ok++
            } else {
                deleteTree(dst) // yarim nusxa qolmasin
                res.failed++
            }
        } catch (e: Exception) {
            res.failed++
        }
    }
    return res
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val roots = remember { buildRoots(context) }

    var rootId by remember { mutableStateOf(roots.first().id) }
    val root = roots.firstOrNull { it.id == rootId } ?: roots.first()
    var current by remember { mutableStateOf(roots.first().dir) }
    var refresh by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var createMenu by remember { mutableStateOf(false) }
    var createKind by remember { mutableStateOf(0) } // 1 = papka, 2 = fayl
    var createName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<File?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<List<File>?>(null) }
    // Linux <-> telefon o'tkazish: har bir bo'limda oxirgi ochilgan papka eslab qolinadi — u nishon papka bo'ladi.
    val lastDirs = remember { mutableMapOf<String, File>() }
    var transfer by remember { mutableStateOf<List<File>?>(null) }
    var transferBusy by remember { mutableStateOf(false) }
    val otherRoot = roots.firstOrNull { it.id != rootId }

    val atRoot = current.absolutePath == root.dir.absolutePath
    val needsPermission = false // Google Play: umumiy xotiraga ruxsat so'ralmaydi
    val selecting = selected.isNotEmpty()

    LaunchedEffect(current) { selected = emptySet(); menuFor = null; lastDirs[rootId] = current }

    fun goUp() {
        current = current.parentFile?.takeIf { !atRoot } ?: root.dir
    }

    BackHandler {
        when {
            selecting -> selected = emptySet()
            !atRoot -> goUp()
            else -> onBack()
        }
    }

    val entries by produceState<List<FmEntry>?>(null, current, refresh) {
        value = null
        value = withContext(Dispatchers.IO) { listDir(current) }
    }

    fun displayPath(f: File): String {
        val rel = try { f.relativeTo(root.dir).path } catch (e: Exception) { "" }
        return if (rel.isEmpty()) root.display else root.display + "/" + rel
    }

    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    fun startTransfer(files: List<File>) {
        val other = otherRoot ?: return
        transfer = files
    }

    Column(Modifier.fillMaxSize().background(BgApp)) {
        // Sarlavha: oddiy holatda "Fayllar", tanlash rejimida amallar.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) {
                Icon(
                    Icons.Filled.Close, contentDescription = tr("Bekor qilish"), tint = TextSecondary,
                    modifier = Modifier.size(22.dp).clickable { selected = emptySet() }
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    tr("Tanlandi: %1\$s", selected.size), color = TextPrimary,
                    fontSize = 18.sp, fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                if (otherRoot != null) {
                    Icon(
                        Icons.Filled.SwapHoriz, contentDescription = tr("Boshqa xotiraga o'tkazish"), tint = TextPrimary,
                        modifier = Modifier.size(24.dp).clickable { startTransfer(selected.map { File(it) }) }
                    )
                    Spacer(Modifier.width(20.dp))
                }
                Icon(
                    Icons.Filled.Share, contentDescription = tr("Ulashish"), tint = TextPrimary,
                    modifier = Modifier.size(22.dp).clickable { shareFiles(context, selected.map { File(it) }) }
                )
                Spacer(Modifier.width(20.dp))
                Icon(
                    Icons.Filled.Delete, contentDescription = tr("O'chirish"), tint = DangerText,
                    modifier = Modifier.size(22.dp).clickable { pendingDelete = selected.map { File(it) } }
                )
            } else {
                Icon(
                    Icons.Filled.ArrowBackIosNew, contentDescription = tr("Orqaga"), tint = TextSecondary,
                    modifier = Modifier.size(16.dp).clickable { if (atRoot) onBack() else goUp() }.mirrorInRtl()
                )
                Spacer(Modifier.width(14.dp))
                Text(tr("Fayllar"), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                if (!needsPermission) {
                    Box {
                        Icon(
                            Icons.Filled.CreateNewFolder, contentDescription = tr("Yangi papka"), tint = TextSecondary,
                            modifier = Modifier.size(24.dp).clickable { createMenu = true }
                        )
                        DropdownMenu(
                            expanded = createMenu,
                            onDismissRequest = { createMenu = false },
                            modifier = Modifier.background(BgCardElevated)
                        ) {
                            DropdownMenuItem(
                                text = { Text(tr("Yangi papka"), color = TextPrimary) },
                                onClick = { createMenu = false; createName = ""; createKind = 1 }
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Yangi fayl"), color = TextPrimary) },
                                onClick = { createMenu = false; createName = ""; createKind = 2 }
                            )
                        }
                    }
                }
            }
        }

        // Ildizlar: Linux / Telefon xotirasi.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            roots.forEach { r ->
                val on = r.id == rootId
                Text(
                    r.label,
                    color = if (on) TextPrimary else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) BgCardElevated else BgApp)
                        .border(1.dp, if (on) AccentPurple else BorderColor, RoundedCornerShape(50))
                        .clickable { rootId = r.id; current = lastDirs[r.id]?.takeIf { it.isDirectory } ?: r.dir }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        Text(
            displayPath(current),
            color = TextMuted,
            style = androidx.compose.ui.text.TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 11.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = entries
            when {

                list == null -> Text(tr("Yuklanmoqda..."), color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))

                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    if (!atRoot) {
                        item(key = "..") {
                            FmRow(
                                icon = Icons.Filled.Folder, name = "..", subtitle = tr("Yuqoriga"),
                                selected = false, selecting = false, showMenu = false,
                                onClick = { goUp() }, onLongClick = {}, menu = {}
                            )
                        }
                    }
                    if (list.isEmpty()) {
                        item(key = "empty") {
                            Text(tr("Bu papka bo'sh"), color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
                        }
                    }
                    items(list, key = { it.file.absolutePath }) { e ->
                        val path = e.file.absolutePath
                        val sub = if (e.isDir) tr("%1\$s ta element", e.childCount)
                        else fmtSize(e.size) + " · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.modified))
                        FmRow(
                            icon = iconFor(e), name = e.name, subtitle = sub,
                            selected = path in selected, selecting = selecting, showMenu = true,
                            onClick = {
                                when {
                                    selecting -> toggle(path)
                                    e.isDir -> current = e.file
                                    else -> openFile(context, e.file)
                                }
                            },
                            onLongClick = { toggle(path) },
                            menu = {
                                Box {
                                    Icon(
                                        Icons.Filled.MoreVert, contentDescription = null, tint = TextMuted,
                                        modifier = Modifier.size(34.dp).clip(CircleShape).clickable { menuFor = path }.padding(7.dp)
                                    )
                                    DropdownMenu(
                                        expanded = menuFor == path,
                                        onDismissRequest = { menuFor = null },
                                        modifier = Modifier.background(BgCardElevated)
                                    ) {
                                        if (!e.isDir) {
                                            DropdownMenuItem(
                                                text = { Text(tr("Ochish"), color = TextPrimary) },
                                                onClick = { menuFor = null; openFile(context, e.file) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(tr("Ulashish"), color = TextPrimary) },
                                                onClick = { menuFor = null; shareFiles(context, listOf(e.file)) }
                                            )
                                        }
                                        if (otherRoot != null) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        tr(if (otherRoot.id == "phone") "Telefon xotirasiga o'tkazish" else "Linuxga o'tkazish"),
                                                        color = TextPrimary
                                                    )
                                                },
                                                onClick = { menuFor = null; startTransfer(listOf(e.file)) }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(tr("Nomini o'zgartirish"), color = TextPrimary) },
                                            onClick = { menuFor = null; renameText = e.name; renaming = e.file }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(tr("Yo'lni nusxalash"), color = TextPrimary) },
                                            onClick = {
                                                menuFor = null
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cm.setPrimaryClip(ClipData.newPlainText("path", displayPath(e.file)))
                                                toast(context, tr("Yo'l nusxalandi"))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(tr("O'chirish"), color = DangerText) },
                                            onClick = { menuFor = null; pendingDelete = listOf(e.file) }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Linux <-> telefon xotirasi: nusxalash yoki ko'chirish
    transfer?.let { items ->
        val other = otherRoot
        if (other == null) {
            transfer = null
        } else {
            val dest = lastDirs[other.id]?.takeIf { it.isDirectory } ?: defaultTransferDir(other)
            val destShown = other.display + (try { dest.relativeTo(other.dir).path } catch (e: Exception) { "" })
                .let { if (it.isEmpty()) "" else "/$it" }
            fun run(move: Boolean) {
                transfer = null
                transferBusy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { transferFiles(items, dest, move) }
                    transferBusy = false
                    selected = emptySet()
                    refresh++
                    val parts = mutableListOf(tr("Bajarildi: %1\$s ta", r.ok))
                    if (r.failed > 0) parts += tr("Bajarilmadi: %1\$s ta", r.failed)
                    if (r.skipped > 0) parts += tr("Symlink'lar o'tkazib yuborildi: %1\$s", r.skipped)
                    Toast.makeText(context, parts.joinToString("\n"), Toast.LENGTH_LONG).show()
                }
            }
            AlertDialog(
                onDismissRequest = { transfer = null },
                containerColor = BgCardElevated,
                title = { Text(tr("Boshqa xotiraga o'tkazish"), color = TextPrimary, fontSize = 17.sp) },
                text = {
                    Column {
                        Text(
                            tr("%1\$s ta element → %2\$s", items.size, other.label),
                            color = TextPrimary, fontSize = 14.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            destShown, color = AccentPurpleText,
                            style = androidx.compose.ui.text.TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr("Boshqa papkaga o'tkazish uchun avval o'sha bo'limda kerakli papkani oching."),
                            color = TextMuted, fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = { run(false) }) { Text(tr("Nusxalash"), color = AccentPurple) }
                        TextButton(onClick = { run(true) }) { Text(tr("Ko'chirish"), color = AccentPurple) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { transfer = null }) { Text(tr("Bekor qilish"), color = TextSecondary) }
                }
            )
        }
    }

    if (transferBusy) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = BgCardElevated,
            confirmButton = {},
            text = { Text(tr("Ko'chirilmoqda…"), color = TextPrimary, fontSize = 14.sp) }
        )
    }

    // Yangi papka / fayl
    if (createKind != 0) {
        AlertDialog(
            onDismissRequest = { createKind = 0 },
            containerColor = BgCardElevated,
            title = {
                Text(tr(if (createKind == 1) "Yangi papka" else "Yangi fayl"), color = TextPrimary, fontSize = 17.sp)
            },
            text = { FmNameField(createName) { createName = it } },
            confirmButton = {
                TextButton(onClick = {
                    val name = createName.trim()
                    val kind = createKind
                    createKind = 0
                    when {
                        name.isEmpty() || '/' in name -> toast(context, tr("Amal bajarilmadi"))
                        File(current, name).exists() -> toast(context, tr("Bu nom allaqachon mavjud"))
                        else -> {
                            val ok = try {
                                if (kind == 1) File(current, name).mkdir() else File(current, name).createNewFile()
                            } catch (e: Exception) { false }
                            if (!ok) toast(context, tr("Amal bajarilmadi"))
                            refresh++
                        }
                    }
                }) { Text(tr("Yaratish"), color = AccentPurple) }
            },
            dismissButton = {
                TextButton(onClick = { createKind = 0 }) { Text(tr("Bekor qilish"), color = TextSecondary) }
            }
        )
    }

    // Nomini o'zgartirish
    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = BgCardElevated,
            title = { Text(tr("Nomini o'zgartirish"), color = TextPrimary, fontSize = 17.sp) },
            text = { FmNameField(renameText) { renameText = it } },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameText.trim()
                    renaming = null
                    val dest = File(target.parentFile, name)
                    when {
                        name.isEmpty() || '/' in name -> toast(context, tr("Amal bajarilmadi"))
                        name == target.name -> {}
                        dest.exists() -> toast(context, tr("Bu nom allaqachon mavjud"))
                        else -> {
                            if (!target.renameTo(dest)) toast(context, tr("Amal bajarilmadi"))
                            refresh++
                        }
                    }
                }) { Text(tr("Saqlash"), color = AccentPurple) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(tr("Bekor qilish"), color = TextSecondary) }
            }
        )
    }

    // O'chirishni tasdiqlash
    pendingDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = BgCardElevated,
            title = { Text(tr("O'chirish"), color = TextPrimary, fontSize = 17.sp) },
            text = {
                Text(
                    if (targets.size == 1) tr("\"%1\$s\" butunlay o'chiriladi.", targets.first().name)
                    else tr("%1\$s ta element butunlay o'chiriladi.", targets.size),
                    color = TextSecondary, fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    selected = emptySet()
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) { targets.count { !deleteTree(it) } }
                        if (failed > 0) toast(context, tr("Amal bajarilmadi"))
                        refresh++
                    }
                }) { Text(tr("O'chirish"), color = DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(tr("Bekor qilish"), color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun FmNameField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(120)) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = AccentPurple,
            unfocusedBorderColor = BorderColor
        )
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FmRow(
    icon: ImageVector,
    name: String,
    subtitle: String,
    selected: Boolean,
    selecting: Boolean,
    showMenu: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menu: @Composable () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Sizing.radiusSm))
            .background(if (selected) BgCardElevated else BgApp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) AccentPurple else BgCardElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (selected) Icons.Filled.Check else icon,
                contentDescription = null,
                tint = if (selected) BgApp else if (icon == Icons.Filled.Folder) AccentPurpleText else TextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showMenu && !selecting) menu()
    }
}
