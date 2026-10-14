package com.mobagent.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Terminal chiqishidagi ANSI SGR ranglarini (ESC[...m) Compose'ning AnnotatedString'iga
 * aylantiradi — Termux'dagi kabi `ls --color`, `grep --color`, `apt`, `git` ranglari ko'rinadi.
 * Rang palitrasi Termux'ning standart palitrasiga yaqin.
 */
private val SGR = Regex("\u001B\\[([0-9;]*)m")

private val BASE16 = arrayOf(
    Color(0xFF000000), Color(0xFFCD0000), Color(0xFF00CD00), Color(0xFFCDCD00),
    Color(0xFF6495ED), Color(0xFFCD00CD), Color(0xFF00CDCD), Color(0xFFE5E5E5),
    Color(0xFF7F7F7F), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFFFFFF00),
    Color(0xFF5C5CFF), Color(0xFFFF00FF), Color(0xFF00FFFF), Color(0xFFFFFFFF)
)

private fun xterm256(n: Int): Color = when {
    n < 16 -> BASE16[n]
    n < 232 -> {
        val i = n - 16
        fun lvl(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(lvl(i / 36), lvl((i / 6) % 6), lvl(i % 6))
    }
    else -> {
        val g = 8 + (n - 232) * 10
        Color(g, g, g)
    }
}

fun ansiToAnnotated(text: String, defaultColor: Color): AnnotatedString = buildAnnotatedString {
    var fg: Color? = null
    var bg: Color? = null
    var bold = false
    var dim = false
    var italic = false
    var underline = false

    fun style(): SpanStyle {
        var c = fg ?: defaultColor
        if (dim) c = c.copy(alpha = 0.6f)
        return SpanStyle(
            color = c,
            background = bg ?: Color.Unspecified,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (underline) TextDecoration.Underline else null
        )
    }

    fun emit(s: String) {
        if (s.isNotEmpty()) withStyle(style()) { append(s) }
    }

    var last = 0
    for (m in SGR.findAll(text)) {
        emit(text.substring(last, m.range.first))
        last = m.range.last + 1
        val codes = m.groupValues[1].split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < codes.size) {
            when (val c = codes[i]) {
                0 -> { fg = null; bg = null; bold = false; dim = false; italic = false; underline = false }
                1 -> bold = true
                2 -> dim = true
                3 -> italic = true
                4 -> underline = true
                22 -> { bold = false; dim = false }
                23 -> italic = false
                24 -> underline = false
                in 30..37 -> fg = BASE16[c - 30]
                39 -> fg = null
                in 40..47 -> bg = BASE16[c - 40]
                49 -> bg = null
                in 90..97 -> fg = BASE16[c - 90 + 8]
                in 100..107 -> bg = BASE16[c - 100 + 8]
                38, 48 -> {
                    var col: Color? = null
                    when (codes.getOrNull(i + 1)) {
                        5 -> { col = xterm256(codes.getOrElse(i + 2) { 0 }.coerceIn(0, 255)); i += 2 }
                        2 -> {
                            col = Color(
                                codes.getOrElse(i + 2) { 0 }.coerceIn(0, 255),
                                codes.getOrElse(i + 3) { 0 }.coerceIn(0, 255),
                                codes.getOrElse(i + 4) { 0 }.coerceIn(0, 255)
                            )
                            i += 4
                        }
                    }
                    if (col != null) { if (c == 38) fg = col else bg = col }
                }
            }
            i++
        }
    }
    emit(text.substring(last))
}
