package com.mobagent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.i18n.tr
import com.mobagent.app.linux.LinuxEnvironment
import com.mobagent.app.linux.LinuxTerminal
import com.mobagent.app.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GNU nano'ga o'xshash o'rnatilgan matn muharriri. Terminalda `nano fayl` yozilganda ochiladi.
 *
 * Haqiqiy nano emas (PTY yo'q), lekin ko'rinishi va tugmalari shunga o'xshash: ^O saqlash,
 * ^X chiqish, ^W qidirish, ^K qatorni kesish, ^U qo'yish, ^_ qatorga o'tish, ^C joylashuv.
 * Pastdagi yorliqlar bosiladigan tugmalar; tashqi klaviaturada Ctrl+harf ham ishlaydi.
 * Fayl Linux yo'li bo'yicha o'qiladi/yoziladi (rootfs ichidagi haqiqiy fayl), shuning uchun
 * terminaldagi `cat fayl` bilan bir xil faylni ko'radi.
 */
private val NanoBg = Color(0xFF000000)
private val NanoBar = Color(0xFFE5E5E5)
private val NanoText = Color(0xFFE5E5E5)
private val NanoDim = Color(0xFF9A9A9A)
private val NanoKeyBg = Color(0xFF2A2A2A)
private val NanoKeyOn = Color(0xFF4A4A4A)

private enum class PromptKind { SAVE_AS, SEARCH, GOTO, SAVE_ON_EXIT }

private fun lineStart(t: String, pos: Int): Int {
    if (pos <= 0) return 0
    val i = t.lastIndexOf('\n', pos - 1)
    return if (i < 0) 0 else i + 1
}

private fun lineEnd(t: String, pos: Int): Int {
    val i = t.indexOf('\n', pos)
    return if (i < 0) t.length else i
}

private fun hostFile(context: android.content.Context, guestPath: String): File =
    LinuxEnvironment.mapGuestPath(context, guestPath) ?: File(guestPath)

@Composable
fun NanoEditor(initialPath: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    var path by remember { mutableStateOf(initialPath) }
    var tv by remember { mutableStateOf(TextFieldValue("")) }
    var original by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var crlf by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf<PromptKind?>(null) }
    var promptText by remember { mutableStateOf("") }
    var exitAfterSave by remember { mutableStateOf(false) }
    var cutBuffer by remember { mutableStateOf("") }
    var lastSearch by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }

    val modified = loaded && tv.text != original
    val mono = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontSize = LinuxTerminal.fontSp.sp,
        lineHeight = (LinuxTerminal.fontSp * 1.45f).sp, color = NanoText
    )
    val small = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp)

    // Faylni o'qish: yo'q bo'lsa — yangi bufer; juda katta yoki ikkilik bo'lsa — ochilmaydi.
    LaunchedEffect(Unit) {
        if (initialPath.isBlank()) {
            loaded = true
            message = tr("[ Yangi bufer ]")
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            try {
                val f = hostFile(context, initialPath)
                when {
                    f.isDirectory -> Pair<String?, String>(null, tr("[ Xato: \"%1\$s\" papka ]", initialPath))
                    !f.exists() -> Pair<String?, String>("", tr("[ Yangi fayl ]"))
                    f.length() > 1024 * 1024 -> Pair<String?, String>(null, tr("[ Xato: fayl juda katta (1 MB dan ortiq) ]"))
                    else -> {
                        val raw = f.readText()
                        if (raw.indexOf('\u0000') >= 0) Pair<String?, String>(null, tr("[ Xato: bu ikkilik fayl ]"))
                        else Pair<String?, String>(raw, tr("[ %1\$s qator o'qildi ]", raw.count { it == '\n' } + if (raw.isNotEmpty() && !raw.endsWith("\n")) 1 else 0))
                    }
                }
            } catch (e: Exception) {
                Pair<String?, String>(null, "[ " + (e.message ?: "error") + " ]")
            }
        }
        val raw = result.first
        if (raw == null) {
            // Ochib bo'lmadi: xabarni terminalga qoldirib, muharrirni yopamiz.
            LinuxTerminal.note("nano: " + result.second.trim('[', ']', ' '))
            onClose()
            return@LaunchedEffect
        }
        crlf = raw.contains("\r\n")
        val text = raw.replace("\r\n", "\n")
        tv = TextFieldValue(text, TextRange(0))
        original = text
        loaded = true
        message = result.second
        try { focus.requestFocus() } catch (_: Exception) {}
    }

    LaunchedEffect(message) {
        if (message.isNotEmpty()) { delay(3500); message = "" }
    }

    fun setCursor(pos: Int) {
        tv = tv.copy(selection = TextRange(pos.coerceIn(0, tv.text.length)))
    }

    fun doWrite(target: String, thenExit: Boolean) {
        val textNow = tv.text
        val wasCrlf = crlf
        scope.launch {
            var err: String? = null
            withContext(Dispatchers.IO) {
                try {
                    val f = hostFile(context, target)
                    f.parentFile?.mkdirs()
                    var out = textNow
                    if (out.isNotEmpty() && !out.endsWith("\n")) out += "\n"
                    if (wasCrlf) out = out.replace("\n", "\r\n")
                    f.writeText(out)
                } catch (e: Exception) {
                    err = e.message ?: e.javaClass.simpleName
                }
            }
            if (err == null) {
                path = target
                original = textNow
                val n = textNow.count { it == '\n' } + if (textNow.isNotEmpty() && !textNow.endsWith("\n")) 1 else 0
                message = tr("[ %1\$s qator yozildi ]", n)
                if (thenExit) onClose()
            } else {
                message = "[ " + tr("Yozib bo'lmadi") + ": $err ]"
            }
        }
    }

    fun save(thenExit: Boolean) {
        if (path.isBlank()) {
            exitAfterSave = thenExit
            promptText = ""
            prompt = PromptKind.SAVE_AS
        } else doWrite(path, thenExit)
    }

    fun exitEditor() {
        if (modified) prompt = PromptKind.SAVE_ON_EXIT else onClose()
    }

    fun search(q: String) {
        if (q.isEmpty()) return
        lastSearch = q
        val t = tv.text
        val from = tv.selection.max.coerceIn(0, t.length)
        var i = t.indexOf(q, from, ignoreCase = true)
        var wrapped = false
        if (i < 0) { i = t.indexOf(q, 0, ignoreCase = true); wrapped = true }
        if (i < 0) {
            message = tr("[ \"%1\$s\" topilmadi ]", q)
        } else {
            tv = tv.copy(selection = TextRange(i, i + q.length))
            if (wrapped) message = tr("[ Qidiruv boshidan davom etdi ]")
        }
    }

    fun gotoLine(n: Int) {
        val t = tv.text
        var pos = 0
        var line = 1
        while (line < n) {
            val nl = t.indexOf('\n', pos)
            if (nl < 0) break
            pos = nl + 1
            line++
        }
        setCursor(pos)
    }

    fun cutLine() {
        val t = tv.text
        val pos = tv.selection.start.coerceIn(0, t.length)
        val ls = lineStart(t, pos)
        val le = lineEnd(t, pos)
        if (ls == le && le >= t.length) return
        val end = if (le < t.length) le + 1 else le
        cutBuffer = t.substring(ls, end).let { if (it.endsWith("\n")) it else it + "\n" }
        val nt = t.removeRange(ls, end)
        tv = TextFieldValue(nt, TextRange(ls.coerceAtMost(nt.length)))
    }

    fun paste() {
        if (cutBuffer.isEmpty()) { message = tr("[ Bufer bo'sh ]"); return }
        val t = tv.text
        val pos = tv.selection.start.coerceIn(0, t.length)
        val at = if (cutBuffer.endsWith("\n")) lineStart(t, pos) else pos
        tv = TextFieldValue(t.substring(0, at) + cutBuffer + t.substring(at), TextRange(at + cutBuffer.length))
    }

    fun curPos() {
        val t = tv.text
        val pos = tv.selection.start.coerceIn(0, t.length)
        val line = t.substring(0, pos).count { it == '\n' } + 1
        val total = t.count { it == '\n' } + 1
        val col = pos - lineStart(t, pos) + 1
        val colTotal = lineEnd(t, pos) - lineStart(t, pos) + 1
        message = "[ line $line/$total (${line * 100 / total}%), col $col/$colTotal, char $pos/${t.length} ]"
    }

    fun insert(s: String) {
        val sel = tv.selection
        val t = tv.text
        tv = TextFieldValue(t.substring(0, sel.min) + s + t.substring(sel.max), TextRange(sel.min + s.length))
    }

    fun moveVertical(lines: Int) {
        val t = tv.text
        var pos = tv.selection.start.coerceIn(0, t.length)
        val col = pos - lineStart(t, pos)
        repeat(kotlin.math.abs(lines)) {
            if (lines < 0) {
                val ls = lineStart(t, pos)
                if (ls > 0) {
                    val prevEnd = ls - 1
                    pos = minOf(lineStart(t, prevEnd) + col, prevEnd)
                } else pos = 0
            } else {
                val le = lineEnd(t, pos)
                if (le < t.length) {
                    val ns = le + 1
                    pos = minOf(ns + col, lineEnd(t, ns))
                } else pos = t.length
            }
        }
        setCursor(pos)
    }

    fun ctrlKey(ch: Char) {
        when (ch.lowercaseChar()) {
            'o' -> save(false)
            'x' -> exitEditor()
            'w' -> { promptText = lastSearch; prompt = PromptKind.SEARCH }
            'k' -> cutLine()
            'u' -> paste()
            'c' -> curPos()
            '_', '/' -> { promptText = ""; prompt = PromptKind.GOTO }
        }
    }

    fun confirmPrompt() {
        val kind = prompt ?: return
        val text = promptText.trim()
        prompt = null
        when (kind) {
            PromptKind.SAVE_AS -> if (text.isNotEmpty()) doWrite(LinuxTerminal.resolvePath(text), exitAfterSave)
            PromptKind.SEARCH -> search(text.ifEmpty { lastSearch })
            PromptKind.GOTO -> text.toIntOrNull()?.let { gotoLine(it) }
            PromptKind.SAVE_ON_EXIT -> {}
        }
    }

    BackHandler {
        when {
            prompt != null -> prompt = null
            else -> exitEditor()
        }
    }

    val name = if (path.isBlank()) tr("Yangi bufer") else path.substringAfterLast('/')

    Column(
        Modifier
            .fillMaxSize()
            .background(NanoBg)
            .statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        // Sarlavha (nano'dagi teskari rangli yuqori qator)
        Row(
            Modifier.fillMaxWidth().background(NanoBar).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("GNU nano", style = small, color = Color.Black, maxLines = 1)
            Text(
                name, style = small.copy(fontWeight = FontWeight.Bold), color = Color.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(if (modified) tr("O'zgargan") else "", style = small, color = Color.Black, maxLines = 1)
        }

        // Matn maydoni
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!loaded) {
                Text(tr("O'qilmoqda…"), style = mono, color = NanoDim, modifier = Modifier.padding(12.dp))
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    BasicTextField(
                        value = tv,
                        onValueChange = { nv ->
                            if (ctrl && nv.text.length == tv.text.length + 1 && nv.selection.start > 0) {
                                ctrl = false
                                ctrlKey(nv.text[nv.selection.start - 1])
                            } else tv = nv
                        },
                        textStyle = mono,
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Default
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp)
                            .focusRequester(focus)
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.isCtrlPressed) {
                                    when (e.key) {
                                        Key.O -> { ctrlKey('o'); true }
                                        Key.X -> { ctrlKey('x'); true }
                                        Key.W -> { ctrlKey('w'); true }
                                        Key.K -> { ctrlKey('k'); true }
                                        Key.U -> { ctrlKey('u'); true }
                                        Key.C -> { ctrlKey('c'); true }
                                        Key.Slash -> { ctrlKey('/'); true }
                                        else -> false
                                    }
                                } else false
                            }
                    )
                }
            }
        }

        // Holat xabari yoki so'rov (prompt) qatori
        val kind = prompt
        if (kind == PromptKind.SAVE_ON_EXIT) {
            Row(
                Modifier.fillMaxWidth().background(NanoKeyBg).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("O'zgarishlar saqlansinmi?"), style = small, color = NanoText, modifier = Modifier.weight(1f))
                PromptBtn(tr("Ha")) { prompt = null; save(true) }
                PromptBtn(tr("Yo'q")) { prompt = null; onClose() }
                PromptBtn(tr("Bekor")) { prompt = null }
            }
        } else if (kind != null) {
            Row(
                Modifier.fillMaxWidth().background(NanoKeyBg).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (kind) {
                        PromptKind.SAVE_AS -> tr("Yozish uchun fayl nomi:")
                        PromptKind.SEARCH -> tr("Qidirish:")
                        else -> tr("Qator raqami:")
                    },
                    style = small.copy(fontWeight = FontWeight.Bold), color = NanoText
                )
                Spacer(Modifier.width(6.dp))
                val promptFocus = remember { FocusRequester() }
                LaunchedEffect(kind) { try { promptFocus.requestFocus() } catch (_: Exception) {} }
                BasicTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    singleLine = true,
                    textStyle = small.copy(color = NanoText),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        keyboardType = if (kind == PromptKind.GOTO) KeyboardType.Number else KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirmPrompt() }),
                    modifier = Modifier.weight(1f).focusRequester(promptFocus)
                )
                PromptBtn("OK") { confirmPrompt() }
                PromptBtn("✕") { prompt = null }
            }
        } else if (message.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                Text(
                    message, style = small, color = Color.White,
                    modifier = Modifier.background(NanoKeyBg).padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }

        // nano'dagi pastki yorliqlar (bosiladigan)
        Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Shortcut("^O", tr("Saqlash")) { ctrlKey('o') }
                Shortcut("^W", tr("Qidirish")) { ctrlKey('w') }
                Shortcut("^K", tr("Kesish")) { ctrlKey('k') }
                Shortcut("^C", tr("Joylashuv")) { ctrlKey('c') }
            }
            Row(Modifier.fillMaxWidth()) {
                Shortcut("^X", tr("Chiqish")) { ctrlKey('x') }
                Shortcut("^_", tr("Qatorga")) { ctrlKey('_') }
                Shortcut("^U", tr("Qo'yish")) { ctrlKey('u') }
                Shortcut("TAB", tr("Tab")) { insert("    ") }
            }
            // Strelkalar va navigatsiya (ekran klaviaturasida yo'q)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                NavKey("CTRL", on = ctrl) { ctrl = !ctrl }
                NavKey("←") { setCursor(tv.selection.start - 1) }
                NavKey("↑") { moveVertical(-1) }
                NavKey("↓") { moveVertical(1) }
                NavKey("→") { setCursor(tv.selection.end + 1) }
                NavKey("HOME") { setCursor(lineStart(tv.text, tv.selection.start)) }
                NavKey("END") { setCursor(lineEnd(tv.text, tv.selection.start)) }
                NavKey("PGUP") { moveVertical(-12) }
                NavKey("PGDN") { moveVertical(12) }
            }
        }
    }
}

@Composable
private fun PromptBtn(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(start = 6.dp)
            .background(NanoBar)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun RowScope.Shortcut(key: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier.weight(1f).clickable(onClick = onClick).padding(horizontal = 3.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            key, color = Color.Black, fontSize = 11.sp, fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(NanoBar).padding(horizontal = 3.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = NanoText, fontSize = 11.sp, fontFamily = JetBrainsMonoFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RowScope.NavKey(label: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .padding(1.dp)
            .height(36.dp)
            .background(if (on) NanoKeyOn else NanoKeyBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) Color.White else NanoText, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
