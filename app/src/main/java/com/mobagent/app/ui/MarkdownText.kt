package com.mobagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.ui.theme.*

/**
 * ILGARI: AI javoblari (masalan, ```html ... ``` kod bloklari) sodda
 * Text() bilan chiqarilardi — natijada uch tirnoqcha, # belgilar, **qalin**
 * kabi markdown belgilari xom matn sifatida ko'rinardi va kod o'qib
 * bo'lmasdi (skrinshotda ko'ringan muammo). To'liq markdown kutubxonasi
 * o'rniga (ortiqcha og'irlik), eng ko'p ishlatiladigan uchta narsani
 * qo'lda ajratamiz: ```kod bloklari```, satr ichidagi `kod`, **qalin matn**.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    baseFontSize: androidx.compose.ui.unit.TextUnit = 13.sp
) {
    val segments = remember(text) { splitCodeFences(text) }
    val context = LocalContext.current
    // SelectionContainer: AI javobini bosib turib belgilash va nusxalash mumkin
    // (ilgari oddiy Text edi — belgilanmasdi).
    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            segments.forEach { seg ->
                when (seg) {
                    is MdSegment.Code -> CodeBlock(seg.lang, seg.code)
                    is MdSegment.Text -> {
                        if (seg.text.isNotBlank()) {
                            val annotated = renderInline(seg.text)
                            // Havola (URL) ustiga bosilsa brauzerda ochiladi.
                            ClickableText(
                                text = annotated,
                                style = TextStyle(
                                    color = TextPrimary,
                                    fontSize = baseFontSize,
                                    lineHeight = baseFontSize * 1.4f
                                ),
                                onClick = { offset ->
                                    annotated.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()?.let {
                                        openUrl(context, it.item)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CodeBlock(lang: String?, code: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CodeBlockBg)
            .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
    ) {
        if (!lang.isNullOrBlank()) {
            Text(
                lang,
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val palette = rememberSyntaxPalette()
            val styled = remember(code, lang, palette) { highlightCode(code, lang, palette) }
            Text(
                styled,
                color = CodeBlockText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

private sealed class MdSegment {
    data class Text(val text: String) : MdSegment()
    data class Code(val lang: String?, val code: String) : MdSegment()
}

private val fenceRegex = Regex("```([a-zA-Z0-9_+-]*)\\n?([\\s\\S]*?)```")

private fun splitCodeFences(raw: String): List<MdSegment> {
    val result = mutableListOf<MdSegment>()
    var lastEnd = 0
    for (match in fenceRegex.findAll(raw)) {
        if (match.range.first > lastEnd) {
            result += MdSegment.Text(raw.substring(lastEnd, match.range.first))
        }
        val lang = match.groupValues[1].ifBlank { null }
        val code = match.groupValues[2].trimEnd('\n')
        result += MdSegment.Code(lang, code)
        lastEnd = match.range.last + 1
    }
    if (lastEnd < raw.length) {
        result += MdSegment.Text(raw.substring(lastEnd))
    }
    if (result.isEmpty()) result += MdSegment.Text(raw)
    return result
}

// Satr ichidagi `kod`, **qalin** va havolalar — AnnotatedString orqali, hech qanday tashqi kutubxonasiz.
private const val URL_TAG = "URL"

// [matn](https://...) yoki yalang'och https://... havola.
private val mdLinkRegex = Regex("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")
private val bareUrlRegex = Regex("https?://[^\\s<>\"'\\[\\]()]+")
private val inlineTokenRegex = Regex("(\\*\\*[^*]+\\*\\*)|(`[^`]+`)|(\\[[^\\]]+\\]\\(https?://[^)\\s]+\\))|(https?://[^\\s<>\"'\\[\\]()]+)")

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, url, Toast.LENGTH_SHORT).show()
    }
}

/** Havola oxiriga yopishgan tinish belgilarini (nuqta, vergul...) havoladan ajratadi. */
private fun splitTrailingPunct(url: String): Pair<String, String> {
    var end = url.length
    while (end > 0 && url[end - 1] in ".,;:!?") end--
    return url.substring(0, end) to url.substring(end)
}

private fun AnnotatedString.Builder.appendLink(label: String, url: String, color: androidx.compose.ui.graphics.Color) {
    pushStringAnnotation(URL_TAG, url)
    withStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline)) { append(label) }
    pop()
}

/** Oddiy matn ichidagi yalang'och havolalarni bosiladigan qiladi. */
private fun AnnotatedString.Builder.appendWithBareLinks(text: String, linkColor: androidx.compose.ui.graphics.Color) {
    var i = 0
    for (m in bareUrlRegex.findAll(text)) {
        if (m.range.first > i) append(text.substring(i, m.range.first))
        val (url, tail) = splitTrailingPunct(m.value)
        appendLink(url, url, linkColor)
        append(tail)
        i = m.range.last + 1
    }
    if (i < text.length) append(text.substring(i))
}

@Composable
private fun renderInline(text: String): AnnotatedString {
    val inlineCodeBg = BgCardElevated
    val inlineCodeFg = TextPrimary
    val linkColor = AccentPurpleText
    return buildAnnotatedString {
        var i = 0
        for (m in inlineTokenRegex.findAll(text)) {
            if (m.range.first > i) append(text.substring(i, m.range.first))
            val token = m.value
            when {
                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                    appendWithBareLinks(token.removePrefix("**").removeSuffix("**"), linkColor)
                }
                token.startsWith("`") -> withStyle(
                    SpanStyle(
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        color = inlineCodeFg,
                        background = inlineCodeBg
                    )
                ) { append(token.removePrefix("`").removeSuffix("`")) }
                token.startsWith("[") -> {
                    val lm = mdLinkRegex.find(token)
                    if (lm != null) appendLink(lm.groupValues[1], lm.groupValues[2], linkColor) else append(token)
                }
                else -> {
                    val (url, tail) = splitTrailingPunct(token)
                    appendLink(url, url, linkColor)
                    append(tail)
                }
            }
            i = m.range.last + 1
        }
        if (i < text.length) append(text.substring(i))
    }
}
