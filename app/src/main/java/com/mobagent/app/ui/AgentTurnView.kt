package com.mobagent.app.ui

import com.mobagent.app.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.history.Block
import com.mobagent.app.history.CmdStatus
import com.mobagent.app.history.DiffKind
import com.mobagent.app.history.DiffState
import com.mobagent.app.history.DiffLine
import com.mobagent.app.ui.theme.*

/**
 * AI'ning BITTA javobi: izoh matni + buyruq kartasi (Bajarish/O'tkazish) +
 * o'zgargan kod diff'i — hammasi bitta ustunda, alohida pufakchalarsiz
 * (VS Code Copilot Chat'dagidek).
 */
@Composable
fun AgentTurnBlocks(
    blocks: List<Block>,
    onRun: (String) -> Unit,
    onSkip: (String) -> Unit,
    onAcceptDiff: (String) -> Unit,
    onDiscardDiff: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Generatsiya shu javobning davomi bo'lib ketayotgan bo'lsa — oxirida jonli matn / "o_o"
    liveText: String? = null,
    thinking: Boolean = false
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Text -> if (b.text.isNotBlank()) MarkdownText(b.text, baseFontSize = 14.sp)
                is Block.Command -> CommandCard(b, onRun, onSkip)
                is Block.Diff -> DiffCard(b, onAcceptDiff, onDiscardDiff)
            }
        }
        if (thinking) {
            if (liveText.isNullOrEmpty()) ThinkingFace() else MarkdownText(liveText, baseFontSize = 14.sp)
        }
    }
}

@Composable
private fun CommandCard(b: Block.Command, onRun: (String) -> Unit, onSkip: (String) -> Unit) {
    var expanded by remember(b.id) { mutableStateOf(b.status == CmdStatus.FAILED) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(BgCardAlt).border(1.dp, BorderColor, shape)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Terminal, null, tint = TextMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (b.step > 1) tr("Buyruq · %1\$s-qadam", b.step) else tr("Buyruq"),
                color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            val (label, color) = when (b.status) {
                CmdStatus.PENDING -> tr("Tasdiq kutilmoqda") to AccentPurple
                CmdStatus.RUNNING -> tr("Bajarilmoqda…") to AccentPurple
                CmdStatus.DONE -> tr("Bajarildi") to SuccessGreen
                CmdStatus.FAILED -> tr("Xato (kod %1\$s)", b.exitCode ?: "?") to DangerText
                CmdStatus.SKIPPED -> tr("O'tkazib yuborildi") to TextMuted
            }
            Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.fillMaxWidth().background(CodeBlockBg).horizontalScroll(rememberScrollState())) {
            val palette = rememberSyntaxPalette()
            val styledCmd = remember(b.cmd, palette) { highlightLine("$ ${b.cmd}", "sh", palette) }
            Text(
                styledCmd,
                color = CodeBlockText, fontSize = 12.sp, lineHeight = 17.sp,
                fontFamily = JetBrainsMonoFamily,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        if (b.status == CmdStatus.PENDING) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(TextPrimary)
                        .clickable { onRun(b.id) }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) { Text(tr("Bajarish"), color = BgApp, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                        .clickable { onSkip(b.id) }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) { Text(tr("O'tkazish"), color = TextSecondary, fontSize = 13.sp) }
            }
        }
        if (b.output.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("Natija"), color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    null, tint = TextMuted, modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) { CodeBlock(null, b.output) }
        }
    }
}

private const val DIFF_COLLAPSED_LINES = 40

@Composable
private fun DiffCard(d: Block.Diff, onAccept: (String) -> Unit, onDiscard: (String) -> Unit) {
    var showAll by remember(d.path, d.added, d.removed) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val addBg = SuccessGreen.copy(alpha = 0.16f)
    val delBg = DangerText.copy(alpha = 0.16f)
    val lines = if (showAll) d.lines else d.lines.take(DIFF_COLLAPSED_LINES)
    val palette = rememberSyntaxPalette()
    val lang = remember(d.path) { normalizeLang(d.path) }
    Column(Modifier.fillMaxWidth().clip(shape).background(CodeBlockBg).border(1.dp, BorderColor, shape)) {
        Row(
            Modifier.fillMaxWidth().background(BgCardAlt).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    d.path.substringAfterLast('/'), color = TextPrimary, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    if (d.isNewFile) tr("yangi fayl") else d.path.substringBeforeLast('/', ""),
                    color = TextMuted, fontSize = 10.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            when (d.state) {
                DiffState.ACCEPTED -> Text(tr("Qabul qilindi"), color = SuccessGreen, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp))
                DiffState.REVERTED -> Text(tr("Bekor qilindi"), color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp))
                DiffState.PENDING -> {}
            }
            Text("+${d.added}", color = SuccessGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text("−${d.removed}", color = DangerText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        // Bekor qilingan diff'ning qatorlari yashiriladi (fayl allaqachon qaytarilgan).
        if (d.state != DiffState.REVERTED) {
            // Barcha qatorlar bitta gorizontal scroll ichida — raqam ustuni va matn tekis turadi.
            Column(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = 280.dp)) {
                lines.forEach { DiffRow(it, addBg, delBg, lang, palette) }
            }
        }
        if (d.state != DiffState.REVERTED && d.lines.size > DIFF_COLLAPSED_LINES) {
            Text(
                if (showAll) tr("Yig'ish") else tr("Yana %1\$s qator…", d.lines.size - DIFF_COLLAPSED_LINES),
                color = AccentPurple, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().clickable { showAll = !showAll }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        if (d.state == DiffState.PENDING && d.id.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(BgCardAlt).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(TextPrimary)
                        .clickable { onAccept(d.id) }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(tr("Qabul qilish"), color = BgApp, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                        .clickable { onDiscard(d.id) }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(tr("Bekor qilish"), color = TextSecondary, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun DiffRow(l: DiffLine, addBg: Color, delBg: Color, lang: String?, palette: SyntaxPalette) {
    if (l.kind == DiffKind.GAP) {
        Text(
            "··· ${l.text}", color = TextMuted, fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
        )
        return
    }
    val bg = when (l.kind) { DiffKind.ADDED -> addBg; DiffKind.REMOVED -> delBg; else -> Color.Transparent }
    val sign = when (l.kind) { DiffKind.ADDED -> "+"; DiffKind.REMOVED -> "−"; else -> " " }
    val signColor = when (l.kind) { DiffKind.ADDED -> SuccessGreen; DiffKind.REMOVED -> DangerText; else -> TextMuted }
    Row(Modifier.fillMaxWidth().background(bg).padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        Text(
            (l.newNo ?: l.oldNo)?.toString().orEmpty().padStart(3),
            color = TextMuted, fontSize = 11.sp, fontFamily = JetBrainsMonoFamily,
            modifier = Modifier.padding(start = 8.dp)
        )
        Text(sign, color = signColor, fontSize = 12.sp, fontFamily = JetBrainsMonoFamily,
            modifier = Modifier.padding(horizontal = 6.dp))
        val styled = remember(l.text, lang, palette) { highlightLine(l.text.ifEmpty { " " }, lang, palette) }
        Text(
            styled, color = CodeBlockText, fontSize = 12.sp, lineHeight = 16.sp,
            fontFamily = JetBrainsMonoFamily, softWrap = false,
            modifier = Modifier.padding(end = 12.dp)
        )
    }
}

/**
 * Chat pastidagi "Working Set": hali ko'rib chiqilmagan (qabul/bekor qilinmagan)
 * o'zgargan fayllar. Bitta bosishda hammasini qabul qilish yoki qaytarish mumkin.
 */
@Composable
fun WorkingSetBar(
    pending: List<Block.Diff>,
    onAcceptAll: () -> Unit,
    onDiscardAll: () -> Unit,
    onAccept: (String) -> Unit,
    onDiscard: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pending.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(BgCard).border(1.dp, BorderColor, shape)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                null, tint = TextMuted, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                tr("O'zgargan fayllar · %1\$s", pending.size),
                color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(TextPrimary)
                    .clickable { onAcceptAll() }.padding(horizontal = 10.dp, vertical = 5.dp)
            ) { Text(tr("Qabul"), color = BgApp, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                    .clickable { onDiscardAll() }.padding(horizontal = 10.dp, vertical = 5.dp)
            ) { Text(tr("Bekor"), color = TextSecondary, fontSize = 11.sp) }
        }
        if (expanded) {
            pending.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        d.path.substringAfterLast('/'), color = TextPrimary, fontSize = 12.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text("+${d.added}", color = SuccessGreen, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("−${d.removed}", color = DangerText, fontSize = 11.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(tr("Qabul"), color = AccentPurple, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onAccept(d.id) }.padding(4.dp))
                    Text(tr("Bekor"), color = TextMuted, fontSize = 11.sp,
                        modifier = Modifier.clickable { onDiscard(d.id) }.padding(4.dp))
                }
            }
        }
    }
}
