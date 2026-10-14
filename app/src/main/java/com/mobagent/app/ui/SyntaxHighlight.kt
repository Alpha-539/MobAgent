package com.mobagent.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.mobagent.app.ui.theme.CodeBlockBg

/**
 * Yengil, kutubxonasiz sintaksis bo'yash (qator-qator, regex asosida).
 * Diff kartasi, kod bloklari va buyruq kartasida ishlatiladi.
 * CHEKLOV: har qator alohida bo'yaladi — ko'p qatorli izoh (/* ... */) yoki
 * ko'p qatorli satr faqat o'z qatori doirasida to'g'ri ko'rinadi.
 */
data class SyntaxPalette(
    val keyword: Color, val string: Color, val comment: Color, val number: Color,
    val type: Color, val function: Color, val tag: Color, val attr: Color
)

private val DarkSyntax = SyntaxPalette(
    keyword = Color(0xFFC792EA), string = Color(0xFFC3E88D), comment = Color(0xFF6B7089),
    number = Color(0xFFF78C6C), type = Color(0xFFFFCB6B), function = Color(0xFF82AAFF),
    tag = Color(0xFFF07178), attr = Color(0xFF89DDFF)
)
private val LightSyntax = SyntaxPalette(
    keyword = Color(0xFF8E24AA), string = Color(0xFF2E7D32), comment = Color(0xFF8A8AA0),
    number = Color(0xFFD84315), type = Color(0xFF9A6700), function = Color(0xFF1565C0),
    tag = Color(0xFFC62828), attr = Color(0xFF00838F)
)

@Composable
fun rememberSyntaxPalette(): SyntaxPalette {
    val dark = CodeBlockBg.luminance() < 0.5f
    return remember(dark) { if (dark) DarkSyntax else LightSyntax }
}

private enum class Tok { KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, TAG, ATTR }

private class Family(val regex: Regex, val classify: (MatchResult, String) -> Tok?)

private val C_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "override", "open",
    "private", "public", "protected", "internal", "static", "final", "abstract", "const", "lateinit",
    "if", "else", "when", "for", "while", "do", "return", "break", "continue", "in", "is", "as", "try",
    "catch", "finally", "throw", "throws", "import", "package", "new", "this", "super", "null", "true",
    "false", "void", "function", "let", "async", "await", "suspend", "extends", "implements", "typeof",
    "export", "default", "from", "switch", "case", "struct", "impl", "fn", "pub", "use", "mut", "self",
    "undefined", "int", "long", "float", "double", "boolean", "char", "string", "number", "any"
)
private val PY_KEYWORDS = setOf(
    "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as", "try",
    "except", "finally", "raise", "with", "lambda", "pass", "break", "continue", "in", "is", "not",
    "and", "or", "None", "True", "False", "self", "yield", "async", "await", "global", "del", "assert"
)
private val SH_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
    "in", "return", "exit", "export", "local", "true", "false"
)
private val SH_COMMANDS = setOf(
    "echo", "printf", "cat", "ls", "cd", "mkdir", "rm", "cp", "mv", "touch", "chmod", "grep", "sed",
    "awk", "tee", "find", "head", "tail", "pwd", "curl", "wget", "tar", "unzip", "python", "python3",
    "node", "npm", "pip", "git", "pkg", "apt"
)

private fun q(s: String) = Regex(s)

private val cLike = Family(
    q("""(//.*|/\*.*?\*/|/\*.*${'$'})|("(?:\\.|[^"\\])*"?|'(?:\\.|[^'\\])*'?|`(?:\\.|[^`\\])*`?)|(\b0x[0-9a-fA-F]+\b|\b\d+(?:\.\d+)?[fFlLuU]?\b)|(@?[A-Za-z_][A-Za-z0-9_]*)""")
) { m, line ->
    when {
        m.groups[1] != null -> Tok.COMMENT
        m.groups[2] != null -> Tok.STRING
        m.groups[3] != null -> Tok.NUMBER
        else -> {
            val w = m.groupValues[4]
            when {
                w.startsWith("@") -> Tok.TYPE
                w in C_KEYWORDS -> Tok.KEYWORD
                w[0].isUpperCase() -> Tok.TYPE
                line.getOrNull(m.range.last + 1) == '(' -> Tok.FUNCTION
                else -> null
            }
        }
    }
}

private fun scriptFamily(keywords: Set<String>, commands: Set<String>) = Family(
    q("""(#.*)|("(?:\\.|[^"\\])*"?|'[^']*'?)|(\b\d+(?:\.\d+)?\b)|(\$\{?[A-Za-z_]\w*\}?|[A-Za-z_][A-Za-z0-9_]*)""")
) { m, line ->
    when {
        m.groups[1] != null -> Tok.COMMENT
        m.groups[2] != null -> Tok.STRING
        m.groups[3] != null -> Tok.NUMBER
        else -> {
            val w = m.groupValues[4]
            when {
                w.startsWith("$") -> Tok.ATTR
                w in keywords -> Tok.KEYWORD
                w in commands -> Tok.FUNCTION
                w[0].isUpperCase() -> Tok.TYPE
                line.getOrNull(m.range.last + 1) == '(' -> Tok.FUNCTION
                else -> null
            }
        }
    }
}

private val python = scriptFamily(PY_KEYWORDS, emptySet())
private val shell = scriptFamily(SH_KEYWORDS, SH_COMMANDS)

private val markup = Family(
    q("""(<!--.*?-->|<!--.*${'$'})|(</?[A-Za-z][\w:-]*)|("[^"]*"?|'[^']*'?)|([A-Za-z_:][\w:.-]*)(?==)|(/?>)""")
) { m, _ ->
    when {
        m.groups[1] != null -> Tok.COMMENT
        m.groups[2] != null || m.groups[5] != null -> Tok.TAG
        m.groups[3] != null -> Tok.STRING
        else -> Tok.ATTR
    }
}

private val css = Family(
    q("""(/\*.*?\*/|/\*.*${'$'})|("[^"]*"?|'[^']*'?)|(#[0-9a-fA-F]{3,8}\b)|([.#][A-Za-z_][\w-]*)|(@[\w-]+)|([a-zA-Z-]+)(?=\s*:)|(\b\d+(?:\.\d+)?(?:px|em|rem|%|vh|vw|s|ms)?)""")
) { m, _ ->
    when {
        m.groups[1] != null -> Tok.COMMENT
        m.groups[2] != null -> Tok.STRING
        m.groups[3] != null || m.groups[7] != null -> Tok.NUMBER
        m.groups[4] != null -> Tok.TYPE
        m.groups[5] != null -> Tok.KEYWORD
        else -> Tok.ATTR
    }
}

private val jsonFam = Family(
    q("""("(?:\\.|[^"\\])*")(\s*:)?|(-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|\b(true|false|null)\b""")
) { m, _ ->
    when {
        m.groups[1] != null -> if (m.groups[2] != null) Tok.ATTR else Tok.STRING
        m.groups[3] != null -> Tok.NUMBER
        else -> Tok.KEYWORD
    }
}

private fun familyFor(lang: String?): Family? = when (normalizeLang(lang)) {
    "kt", "java", "js", "ts", "c", "cpp", "cs", "go", "rs", "swift", "php", "dart" -> cLike
    "py", "rb", "yaml", "toml" -> python
    "sh" -> shell
    "html", "xml", "svg" -> markup
    "css" -> css
    "json" -> jsonFam
    else -> null
}

/** "kotlin"/"Kotlin"/"kts"/"App.kt" -> "kt" va h.k.; noma'lum bo'lsa null. */
fun normalizeLang(raw: String?): String? {
    val l = raw?.trim()?.lowercase()?.substringAfterLast('.')?.takeIf { it.isNotEmpty() } ?: return null
    return when (l) {
        "kotlin", "kts" -> "kt"
        "javascript", "jsx", "mjs", "tsx", "typescript" -> if (l.startsWith("t")) "ts" else "js"
        "python" -> "py"
        "bash", "shell", "zsh", "console", "terminal" -> "sh"
        "htm" -> "html"
        "yml" -> "yaml"
        "c++", "cc", "h", "hpp" -> "cpp"
        "csharp" -> "cs"
        "golang" -> "go"
        "rust" -> "rs"
        else -> l
    }
}

fun isHighlightable(lang: String?): Boolean = familyFor(lang) != null

fun highlightLine(line: String, lang: String?, p: SyntaxPalette): AnnotatedString {
    val fam = familyFor(lang) ?: return AnnotatedString(line)
    return buildAnnotatedString {
        var i = 0
        for (m in fam.regex.findAll(line)) {
            if (m.range.first > i) append(line.substring(i, m.range.first))
            val tok = fam.classify(m, line)
            if (tok == null) {
                append(m.value)
            } else {
                val color = when (tok) {
                    Tok.KEYWORD -> p.keyword; Tok.STRING -> p.string; Tok.COMMENT -> p.comment
                    Tok.NUMBER -> p.number; Tok.TYPE -> p.type; Tok.FUNCTION -> p.function
                    Tok.TAG -> p.tag; Tok.ATTR -> p.attr
                }
                withStyle(SpanStyle(color = color)) { append(m.value) }
            }
            i = m.range.last + 1
        }
        if (i < line.length) append(line.substring(i))
    }
}

fun highlightCode(code: String, lang: String?, p: SyntaxPalette): AnnotatedString {
    if (!isHighlightable(lang)) return AnnotatedString(code)
    return buildAnnotatedString {
        code.split('\n').forEachIndexed { idx, line ->
            if (idx > 0) append('\n')
            append(highlightLine(line, lang, p))
        }
    }
}
