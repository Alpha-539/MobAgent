package com.mobagent.app.agent

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Fayl yozilgandan/tahrirlangandan keyin uni tez tekshiradi (JSON, HTML/XML).
 * Natija modelga qaytariladi: kichik model o'z xatosini ko'rib, keyingi
 * qadamda tuzata oladi. Tekshiruv faqat OGOHLANTIRADI — faylni o'zgartirmaydi.
 * Null = xato topilmadi (yoki bu turdagi fayl tekshirilmaydi).
 */
object FileValidator {
    private const val MAX_CHARS = 512 * 1024

    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
        "meta", "param", "source", "track", "wbr"
    )
    // HTML'da yopuvchi tegi ixtiyoriy bo'lgan teglar — yopilmagani xato emas.
    private val OPTIONAL_END = setOf(
        "p", "li", "td", "th", "tr", "thead", "tbody", "tfoot", "option",
        "dd", "dt", "head", "body", "html", "colgroup"
    )
    private val TAG = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9:-]*)(?:"[^"]*"|'[^']*'|[^>"'])*?(/?)>""")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val RAW_BLOCK = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
    private val json = Json

    fun validate(file: File, text: String): String? {
        if (text.length > MAX_CHARS) return null
        return when (file.extension.lowercase()) {
            "json" -> validateJson(text)
            "html", "htm" -> validateMarkup(text, html = true)
            "xml", "svg" -> validateMarkup(text, html = false)
            else -> null
        }
    }

    private fun validateJson(text: String): String? = try {
        json.parseToJsonElement(text); null
    } catch (e: Exception) {
        "invalid JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty().take(160)}"
    }

    private fun validateMarkup(raw: String, html: Boolean): String? {
        // Izoh va script/style ichini bo'sh joy bilan almashtiramiz (qator raqamlari saqlansin).
        fun blank(m: MatchResult) = m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
        var text = COMMENT.replace(raw, ::blank)
        // <script>/<style> butunlay bo'shatiladi (ichidagi "<" teg emas); ular o'zi muvozanatli.
        if (html) text = RAW_BLOCK.replace(text, ::blank)
        val stack = ArrayDeque<Pair<String, Int>>()
        for (m in TAG.findAll(text)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].let { if (html) it.lowercase() else it }
            val selfClosed = m.groupValues[3] == "/"
            val line = text.substring(0, m.range.first).count { it == '\n' } + 1
            if (!closing) {
                if (selfClosed || (html && name in VOID)) continue
                stack.addLast(name to line)
            } else {
                if (html && name in VOID) continue
                val idx = stack.indexOfLast { it.first == name }
                if (idx < 0) return "line $line: closing </$name> has no matching opening tag"
                // Orasidagi ochiq teglar faqat HTML'da ixtiyoriy-yopiladiganlar bo'lsa, ruxsat.
                for (i in stack.size - 1 downTo idx + 1) {
                    val (inner, innerLine) = stack[i]
                    if (!(html && inner in OPTIONAL_END)) return "line $innerLine: <$inner> is never closed (before </$name> on line $line)"
                }
                while (stack.size > idx) stack.removeLast()
            }
        }
        val open = stack.firstOrNull { !(html && it.first in OPTIONAL_END) }
        return open?.let { "line ${it.second}: <${it.first}> is never closed" }
    }
}
