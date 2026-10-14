package com.mobagent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.i18n.mirrorInRtl
import com.mobagent.app.i18n.tr
import com.mobagent.app.linux.LinuxEnvironment
import com.mobagent.app.linux.LinuxTerminal
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.launch

// Terminal doim qora (Termux kabi) — ilovaning yorug/qorong'i mavzusiga bog'liq emas.
private val TermBg = Color(0xFF000000)
private val TermKeys = Color(0xFF181818)
private val TermKey = Color(0xFF2A2A2A)
private val TermKeyOn = Color(0xFF4A4A4A)
private val TermText = Color(0xFFE5E5E5)
private val TermDim = Color(0xFFB4B4B4)
private val TermMuted = Color(0xFF7C7C7C)
private val TermGreen = Color(0xFF00CD00)
private val TermBlue = Color(0xFF6495ED)

private fun promptDir(dir: String): String = when {
    dir == "/root" -> "~"
    dir.startsWith("/root/") -> "~" + dir.removePrefix("/root")
    else -> dir
}

/** Termux'dagi kabi ixcham prompt: `~/papka # ` (papka — ko'k, belgi — yashil). */
private fun promptText(dir: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = TermBlue, fontWeight = FontWeight.Bold)) { append(promptDir(dir)) }
    append(" ")
    withStyle(SpanStyle(color = TermGreen, fontWeight = FontWeight.Bold)) { append("#") }
    append(" ")
}

private fun commonPrefix(items: List<String>): String {
    var p = items.first()
    for (s in items) {
        var i = 0
        while (i < p.length && i < s.length && p[i] == s[i]) i++
        p = p.substring(0, i)
        if (p.isEmpty()) break
    }
    return p
}

/**
 * Yon paneldagi "Terminal" tugmasi ochadigan to'liq ekranli qatlam. ChatScreen ustiga
 * chiziladi (navigatsiya emas), shuning uchun model yuklanib turadi, yozilayotgan matn va
 * davom etayotgan generatsiya yo'qolmaydi. Holat [LinuxTerminal] da — ekran yopilsa ham saqlanadi.
 *
 * Termux'ga o'xshash: prompt chiqish bilan bir oqimda (qator ichida), ANSI ranglar, pastda
 * qo'shimcha tugmalar qatori (ESC, TAB, CTRL, ^C, strelkalar, HOME/END, PGUP/PGDN, / - | ~),
 * Tab bilan to'ldirish, ↑/↓ tarix, matn o'lchamini o'zgartirish va nusxa olish.
 */
@Composable
fun LinuxTerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }

    val active = LinuxEnvironment.isActive(context)
    val lines = LinuxTerminal.lines
    val running = LinuxTerminal.running
    val fontSp = LinuxTerminal.fontSp
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var ctrl by remember { mutableStateOf(false) }
    var histIdx by remember { mutableStateOf(-1) } // -1: yangi qator, aks holda tarix indeksi
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val mono = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = fontSp.sp, lineHeight = (fontSp * 1.4f).sp)

    // `ls` ustunlari ekran kengligiga mos bo'lishi uchun belgilar sonini hisoblaymiz (JetBrains Mono ~0.6em).
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    LaunchedEffect(screenWidthDp, fontSp) {
        LinuxTerminal.columns = (((screenWidthDp - 24) / (fontSp * 0.6f)).toInt()).coerceIn(30, 200)
    }

    // Banner (bo'sh holat) ham elementlardan biri: [banner?] + qatorlar + prompt.
    val bannerCount = if (lines.isEmpty()) 1 else 0
    val totalItems = bannerCount + lines.size + 1

    fun scrollToEnd() = scope.launch { listState.scrollToItem((totalItems - 1).coerceAtLeast(0)) }

    // Foydalanuvchi yuqoriga aylantirib o'qiyotgan bo'lsa, yangi qatorlar uni pastga tortmasin.
    LaunchedEffect(totalItems) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= totalItems - 6) listState.scrollToItem(totalItems - 1)
    }
    // Yozayotganda har doim prompt ko'rinib tursin.
    LaunchedEffect(input.text) { listState.scrollToItem((totalItems - 1).coerceAtLeast(0)) }
    LaunchedEffect(active, LinuxTerminal.editorPath) {
        if (active && LinuxTerminal.editorPath == null) try { focus.requestFocus() } catch (_: Exception) {}
    }

    fun setText(t: String, cursorAtEnd: Boolean = true) {
        input = TextFieldValue(t, if (cursorAtEnd) TextRange(t.length) else TextRange(0))
    }

    fun send() {
        if (!active || running || input.text.isBlank()) return
        LinuxTerminal.submit(context, input.text)
        setText("")
        histIdx = -1
        draft = ""
    }

    fun histUp() {
        val h = LinuxTerminal.history
        if (h.isEmpty()) return
        if (histIdx == -1) { draft = input.text; histIdx = h.size - 1 } else if (histIdx > 0) histIdx--
        setText(h[histIdx])
    }

    fun histDown() {
        val h = LinuxTerminal.history
        if (histIdx == -1) return
        if (histIdx < h.size - 1) { histIdx++; setText(h[histIdx]) } else { histIdx = -1; setText(draft) }
    }

    fun doTab() {
        if (!active || running) return
        val sel = input.selection.start.coerceIn(0, input.text.length)
        val before = input.text.substring(0, sel)
        val after = input.text.substring(sel)
        scope.launch {
            val cands = LinuxTerminal.complete(context, before)
            if (cands.isEmpty()) return@launch
            val word = before.substringAfterLast(' ')
            val head = before.removeSuffix(word)
            var completed = commonPrefix(cands)
            if (cands.size == 1 && !completed.endsWith("/")) completed += " "
            if (completed.length < word.length) completed = word
            val newBefore = head + completed
            input = TextFieldValue(newBefore + after, TextRange(newBefore.length))
            if (cands.size > 1) LinuxTerminal.note(cands.joinToString("  ") { it.substringAfterLast('/').ifEmpty { it } })
        }
    }

    fun ctrlKey(ch: Char) {
        val t = input.text
        val sel = input.selection.start.coerceIn(0, t.length)
        when (ch.lowercaseChar()) {
            'c' -> if (running) LinuxTerminal.stop() else { if (t.isNotEmpty()) LinuxTerminal.cancelInput(t); setText(""); histIdx = -1 }
            'l' -> LinuxTerminal.clear()
            'u' -> input = TextFieldValue(t.substring(sel), TextRange(0))
            'k' -> input = TextFieldValue(t.substring(0, sel), TextRange(sel))
            'a' -> input = input.copy(selection = TextRange(0))
            'e' -> input = input.copy(selection = TextRange(t.length))
            'w' -> {
                val head = t.substring(0, sel).trimEnd()
                val cut = head.lastIndexOf(' ') + 1
                input = TextFieldValue(head.substring(0, cut) + t.substring(sel), TextRange(cut))
            }
        }
    }

    fun moveCursor(delta: Int) {
        val pos = (input.selection.start + delta).coerceIn(0, input.text.length)
        input = input.copy(selection = TextRange(pos))
    }

    fun insert(s: String) {
        val sel = input.selection
        val t = input.text
        val nt = t.substring(0, sel.min) + s + t.substring(sel.max)
        input = TextFieldValue(nt, TextRange(sel.min + s.length))
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(TermBg)
            .statusBarsPadding().navigationBarsPadding().imePadding()
            // Pastdagi chat tap'larni olib qolmasligi uchun; bo'sh joyga bosilsa klaviatura chiqadi.
            .pointerInput(Unit) { detectTapGestures { try { focus.requestFocus() } catch (_: Exception) {} } }
    ) {
        // Ixcham sarlavha
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ArrowBackIosNew, contentDescription = tr("Orqaga"), tint = TermDim,
                modifier = Modifier.size(16.dp).clickable(onClick = onBack).mirrorInRtl()
            )
            Spacer(Modifier.width(12.dp))
            Text(tr("Terminal"), color = TermText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            HeaderBtn("A−") { if (LinuxTerminal.fontSp > 8) LinuxTerminal.fontSp -= 1 }
            HeaderBtn("A+") { if (LinuxTerminal.fontSp < 24) LinuxTerminal.fontSp += 1 }
            HeaderBtn(tr("Nusxa")) {
                clipboard.setText(AnnotatedString(lines.joinToString("\n") { l ->
                    if (l.kind == LinuxTerminal.Kind.CMD) "${promptDir(l.dir)} # ${l.text}"
                    else l.text.replace(Regex("\u001B\\[[0-9;]*m"), "")
                }))
            }
            if (!running && lines.isNotEmpty()) HeaderBtn(tr("Tozalash")) { LinuxTerminal.clear() }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!active) {
                Text(
                    tr("Linux muhiti yoqilmagan yoki o'rnatilmagan. Sozlamalar > Linux muhiti bo'limidan yoqing."),
                    color = TermMuted, fontSize = 13.sp, modifier = Modifier.padding(16.dp)
                )
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
                    ) {
                        if (lines.isEmpty()) {
                            item(key = "banner") {
                                Text(
                                    tr("Ubuntu terminali (root).\nTab — to'ldirish, ↑/↓ — buyruqlar tarixi, CTRL+C — to'xtatish. `nano fayl` — o'rnatilgan muharrir. Boshqa to'liq ekranli dasturlar (vim, top) hozircha ishlamaydi."),
                                    style = mono, color = TermMuted, modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                        }
                        val snapshot = lines.toList()
                        items(snapshot.size) { i ->
                            val line = snapshot[i]
                            when (line.kind) {
                                LinuxTerminal.Kind.CMD -> Text(
                                    buildAnnotatedString {
                                        append(promptText(line.dir))
                                        withStyle(SpanStyle(color = TermText)) { append(line.text) }
                                    },
                                    style = mono, modifier = Modifier.fillMaxWidth()
                                )
                                LinuxTerminal.Kind.OUT -> Text(
                                    ansiToAnnotated(line.text.ifEmpty { " " }, TermText),
                                    style = mono, modifier = Modifier.fillMaxWidth()
                                )
                                LinuxTerminal.Kind.INFO -> Text(
                                    line.text.ifEmpty { " " }, style = mono, color = TermMuted,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        // Prompt chiqish bilan bir oqimda — Termux'dagi kabi qator ichida yoziladi.
                        item(key = "prompt") {
                            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.Top) {
                                Text(if (running) AnnotatedString("") else promptText(LinuxTerminal.cwd), style = mono)
                                BasicTextField(
                                    value = input,
                                    onValueChange = { nv ->
                                        if (ctrl && nv.text.length == input.text.length + 1 && nv.selection.start > 0) {
                                            // CTRL bosilgan: yozilgan harf buyruq sifatida talqin qilinadi.
                                            ctrl = false
                                            ctrlKey(nv.text[nv.selection.start - 1])
                                        } else {
                                            input = nv
                                            if (histIdx != -1 && nv.text != LinuxTerminal.history.getOrNull(histIdx)) histIdx = -1
                                        }
                                    },
                                    enabled = active,
                                    readOnly = running,
                                    textStyle = mono.copy(color = TermText),
                                    cursorBrush = SolidColor(TermText),
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.None,
                                        autoCorrect = false,
                                        // Parol turi klaviaturaning avto-tuzatish/tavsiyalarini o'chiradi (Termux kabi).
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Send
                                    ),
                                    keyboardActions = KeyboardActions(onSend = { send() }),
                                    modifier = Modifier.weight(1f).focusRequester(focus)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Termux'dagi kabi qo'shimcha tugmalar qatori.
        if (active) {
            Column(Modifier.fillMaxWidth().background(TermKeys).padding(vertical = 2.dp)) {
                KeyRow {
                    Key("ESC") { setText(""); histIdx = -1; ctrl = false }
                    Key("/") { insert("/") }
                    Key("-") { insert("-") }
                    Key("HOME") { input = input.copy(selection = TextRange(0)) }
                    Key("↑") { histUp() }
                    Key("END") { input = input.copy(selection = TextRange(input.text.length)) }
                    Key("PGUP") { scope.launch { listState.scrollBy(-listState.layoutInfo.viewportSize.height * 0.8f) } }
                }
                KeyRow {
                    Key("TAB") { doTab() }
                    Key("CTRL", on = ctrl) { ctrl = !ctrl }
                    Key("^C") { ctrlKey('c'); ctrl = false }
                    Key("←") { moveCursor(-1) }
                    Key("↓") { histDown() }
                    Key("→") { moveCursor(1) }
                    Key("PGDN") { scope.launch { listState.scrollBy(listState.layoutInfo.viewportSize.height * 0.8f) } }
                }
                KeyRow {
                    Key("|") { insert("|") }
                    Key("~") { insert("~") }
                    Key("_") { insert("_") }
                    Key("\"") { insert("\"") }
                    Key("'") { insert("'") }
                    Key("&") { insert("&") }
                    Key("⏎") { send() }
                }
            }
        }
    }
    // nano muharriri terminal ustida to'liq ekranda chiziladi.
    LinuxTerminal.editorPath?.let { ep ->
        NanoEditor(initialPath = ep, onClose = { LinuxTerminal.editorPath = null })
    }
    }
}

@Composable
private fun HeaderBtn(label: String, onClick: () -> Unit) {
    Text(
        label, color = TermDim, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), content = content)
}

@Composable
private fun RowScope.Key(label: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .padding(1.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) TermKeyOn else TermKey)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) Color.White else TermText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
