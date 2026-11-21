package com.xfiles.feature.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

internal class CodeTheme(
    val keyword: SpanStyle,
    val string: SpanStyle,
    val comment: SpanStyle,
    val number: SpanStyle,
    val annotation: SpanStyle,
    val typeName: SpanStyle,
    val functionCall: SpanStyle,
    val tag: SpanStyle,
    val attribute: SpanStyle,
    val heading: SpanStyle,
    val link: SpanStyle,
    val code: SpanStyle,
    val emphasis: SpanStyle,
    val strong: SpanStyle,
    val marker: SpanStyle,
    val quote: SpanStyle,
    val operator: SpanStyle,
)

/** Android Studio's dark scheme, on the code card's dark background. */
private val Darcula = CodeTheme(
    keyword = SpanStyle(color = Color(0xFFCF8E6D)),
    string = SpanStyle(color = Color(0xFF6AAB73)),
    comment = SpanStyle(color = Color(0xFF7A7E85), fontStyle = FontStyle.Italic),
    number = SpanStyle(color = Color(0xFF2AACB8)),
    annotation = SpanStyle(color = Color(0xFFB3AE60)),
    typeName = SpanStyle(color = Color(0xFF4EC9B0)),
    functionCall = SpanStyle(color = Color(0xFFDCDCAA)),
    tag = SpanStyle(color = Color(0xFFD5B778)),
    attribute = SpanStyle(color = Color(0xFFBABABA)),
    heading = SpanStyle(color = Color(0xFFCF8E6D), fontWeight = FontWeight.Bold),
    link = SpanStyle(color = Color(0xFF548AF7), textDecoration = TextDecoration.Underline),
    code = SpanStyle(color = Color(0xFF6AAB73)),
    emphasis = SpanStyle(fontStyle = FontStyle.Italic),
    strong = SpanStyle(fontWeight = FontWeight.Bold),
    marker = SpanStyle(color = Color(0xFFCF8E6D)),
    quote = SpanStyle(color = Color(0xFF7A7E85)),
    operator = SpanStyle(color = Color(0xFFD4D4D4)),
)

/** IntelliJ light scheme for light backgrounds. */
private val IntelliJLight = CodeTheme(
    keyword = SpanStyle(color = Color(0xFF0033B3)),
    string = SpanStyle(color = Color(0xFF067D17)),
    comment = SpanStyle(color = Color(0xFF8C8C8C), fontStyle = FontStyle.Italic),
    number = SpanStyle(color = Color(0xFF1750EB)),
    // Darker than IntelliJ's editor yellow, for contrast on the white page.
    annotation = SpanStyle(color = Color(0xFF9E880D)),
    typeName = SpanStyle(color = Color(0xFF000000), fontWeight = FontWeight.Bold),
    functionCall = SpanStyle(color = Color(0xFF7A7A43)),
    tag = SpanStyle(color = Color(0xFF0033B3)),
    attribute = SpanStyle(color = Color(0xFF174AD4)),
    heading = SpanStyle(color = Color(0xFF0033B3), fontWeight = FontWeight.Bold),
    link = SpanStyle(color = Color(0xFF006DCC), textDecoration = TextDecoration.Underline),
    code = SpanStyle(color = Color(0xFF067D17)),
    emphasis = SpanStyle(fontStyle = FontStyle.Italic),
    strong = SpanStyle(fontWeight = FontWeight.Bold),
    marker = SpanStyle(color = Color(0xFF0033B3)),
    quote = SpanStyle(color = Color(0xFF8C8C8C)),
    operator = SpanStyle(color = Color(0xFF1E1E1E)),
)

/** Longer text stays plain: colouring is per block, and a huge block would stall the frame. */
private const val MAX_HIGHLIGHT_CHARS = 200_000

/**
 * [text] coloured as Android Studio colours [language], or `null` for plain
 * text. Each block is coloured on its own, so a comment or code fence that
 * starts in an earlier block isn't known here.
 */
internal fun highlightCode(language: String?, text: String, dark: Boolean = true): AnnotatedString? {
    val lang = language?.lowercase() ?: return null
    if (text.length > MAX_HIGHLIGHT_CHARS) return null
    val theme = if (dark) Darcula else IntelliJLight
    return when (lang) {
        "markdown", "md" -> highlightMarkdown(text, theme)
        "html", "xml", "svg", "xsd", "plist", "vue", "svelte" -> highlightMarkup(text, theme)
        "text", "txt", "csv", "diff", "patch" -> null
        else -> highlightGeneric(lang, text, theme)
    }
}

private val KEYWORDS = setOf(
    "abstract", "actual", "and", "as", "async", "await", "break", "case", "catch", "class", "companion", "const",
    "continue", "data", "def", "default", "defer", "del", "do", "elif", "else", "enum", "except", "expect", "export",
    "extends", "extern", "false", "final", "finally", "fn", "for", "from", "fun", "func", "function", "go", "if", "impl",
    "implements", "import", "in", "init", "inline", "interface", "internal", "is", "lambda", "lateinit", "let", "loop",
    "match", "mod", "module", "mut", "new", "nil", "None", "not", "null", "object", "open", "operator", "or",
    "override", "package", "pass", "private", "protected", "pub", "public", "raise", "return", "sealed", "self", "Self",
    "static", "struct", "super", "suspend", "switch", "this", "throw", "throws", "trait", "True", "False", "true", "try",
    "type", "typeof", "typealias", "use", "val", "var", "void", "when", "where", "while", "with", "yield", "crate",
    "ref", "dyn", "unsafe", "readonly", "echo", "then", "fi", "esac", "done", "local", "elseif", "end", "nonlocal",
    "global", "assert", "instanceof", "boolean", "int", "long", "float", "double", "char", "byte", "short", "string",
    "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE", "JOIN", "ON", "AND", "OR", "NOT",
    "NULL", "ORDER", "BY", "GROUP", "INTO", "VALUES", "AS", "LIMIT", "PRIMARY", "KEY",
    // C/C++
    "namespace", "template", "typename", "union", "constexpr", "consteval", "constinit", "volatile", "mutable",
    "explicit", "friend", "typedef", "using", "auto", "decltype", "sizeof", "alignof", "alignas", "noexcept",
    "delete", "nullptr", "signed", "unsigned", "register", "goto", "co_await", "co_return", "co_yield",
    "concept", "requires", "static_assert", "static_cast", "dynamic_cast", "reinterpret_cast", "const_cast",
)

private val CPP_LANGUAGES = setOf(
    "c", "cpp", "cc", "cxx", "h", "hpp", "cs", "objc", "m", "mm", "java",
)

private val CPP_BUILTIN_TYPES = setOf(
    "wchar_t", "char8_t", "char16_t", "char32_t", "size_t",
    "int8_t", "int16_t", "int32_t", "int64_t",
    "uint8_t", "uint16_t", "uint32_t", "uint64_t",
    "ptrdiff_t", "intptr_t", "uintptr_t",
    "string", "vector", "map", "set", "list", "deque", "queue", "stack",
    "pair", "tuple", "array", "unordered_map", "unordered_set",
    "shared_ptr", "unique_ptr", "weak_ptr",
    "optional", "variant", "any", "span", "string_view",
    "istream", "ostream", "iostream", "ifstream", "ofstream", "stringstream",
    "mutex", "thread", "future", "promise", "atomic",
)

private val HASH_COMMENTS = setOf(
    "py", "python", "sh", "bash", "zsh", "fish", "shell", "yaml", "yml", "toml", "rb", "ruby", "r", "pl", "perl",
    "ini", "cfg", "conf", "properties", "env", "dockerfile", "makefile", "gemfile", "rakefile", "ps1", "ex", "exs", "jl",
    "nim", "tf", "graphql", "gql",
)

private val DASH_COMMENTS = setOf("sql", "lua", "hs", "haskell")

private val OPERATORS = setOf("->", "=>", "::", "..", "!=", "==", ">=", "<=", "&&", "||", "<<", ">>")

private fun highlightGeneric(lang: String, text: String, t: CodeTheme): AnnotatedString = buildAnnotatedString {
    append(text)
    val hash = lang in HASH_COMMENTS
    val dash = lang in DASH_COMMENTS
    val preprocessor = lang in CPP_LANGUAGES
    val slash = !hash || lang in setOf("php")
    val cppTypes = if (preprocessor) CPP_BUILTIN_TYPES else emptySet()
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            preprocessor && c == '#' && (i == 0 || text[i - 1] == '\n') -> {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                addStyle(t.annotation, i, end)
                i = end
            }
            (slash && text.startsWith("//", i)) || (hash && c == '#') || (dash && text.startsWith("--", i)) -> {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                addStyle(t.comment, i, end)
                i = end
            }
            slash && text.startsWith("/*", i) -> {
                val end = text.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                addStyle(t.comment, i, end)
                i = end
            }
            preprocessor && (c == 'L' || c == 'u' || c == 'U') && i + 1 < n && (text[i + 1] == '"' || text[i + 1] == '\'' ||
                (c == 'u' && text[i + 1] == '8' && i + 2 < n && text[i + 2] == '"')) -> {
                val prefixLen = if (c == 'u' && text[i + 1] == '8') 2 else 1
                val q = text[i + prefixLen]
                var j = i + prefixLen + 1
                while (j < n && text[j] != q) { if (text[j] == '\\') j++; j++ }
                if (j < n) j++
                addStyle(t.string, i, j.coerceAtMost(n))
                i = j.coerceAtMost(n)
            }
            c == '"' || c == '\'' || c == '`' -> {
                // Triple-quoted strings run over lines.
                val triple = text.startsWith("$c$c$c", i)
                var j = if (triple) i + 3 else i + 1
                while (j < n) {
                    if (text[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (triple && text.startsWith("$c$c$c", j)) {
                        j += 3
                        break
                    }
                    if (!triple && text[j] == c) {
                        j++
                        break
                    }
                    if (!triple && c != '`' && text[j] == '\n') break
                    j++
                }
                val end = j.coerceAtMost(n)
                addStyle(t.string, i, end)
                i = end
            }
            c.isDigit() && (i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_')) -> {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                addStyle(t.number, i, j)
                i = j
            }
            c == '@' && i + 1 < n && text[i + 1].isLetter() -> {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.')) j++
                addStyle(t.annotation, i, j)
                i = j
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                when {
                    word in KEYWORDS -> addStyle(t.keyword, i, j)
                    word in cppTypes -> addStyle(t.typeName, i, j)
                    word.length > 1 && word[0].isUpperCase() && word.drop(1).any { it.isLowerCase() } ->
                        addStyle(t.typeName, i, j)
                    j < n && text[j] == '(' -> addStyle(t.functionCall, i, j)
                }
                i = j
            }
            i + 1 < n && OPERATORS.contains(text.substring(i, i + 2)) -> {
                addStyle(t.operator, i, i + 2)
                i += 2
            }
            else -> i++
        }
    }
}

private fun highlightMarkup(text: String, t: CodeTheme): AnnotatedString = buildAnnotatedString {
    append(text)
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text.startsWith("<!--", i) -> {
                val end = text.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                addStyle(t.comment, i, end)
                i = end
            }
            text[i] == '<' -> {
                val end = text.indexOf('>', i).let { if (it < 0) n else it + 1 }
                addStyle(t.tag, i, end)
                var j = i
                while (j < end) {
                    val q = text[j]
                    if (q == '"' || q == '\'') {
                        val close = text.indexOf(q, j + 1).let { if (it < 0 || it >= end) end else it + 1 }
                        addStyle(t.string, j, close)
                        j = close
                    } else {
                        if (q == '=') {
                            var k = j - 1
                            while (k > i && text[k].let { it.isLetterOrDigit() || it == '-' || it == ':' }) k--
                            addStyle(t.attribute, k + 1, j)
                        }
                        j++
                    }
                }
                i = end
            }
            text[i] == '&' -> {
                val end = text.indexOf(';', i).takeIf { it in (i + 1)..(i + 10) }?.plus(1) ?: (i + 1)
                if (end > i + 1) addStyle(t.keyword, i, end)
                i = end
            }
            else -> i++
        }
    }
}

private val INLINE_CODE = Regex("`[^`\n]+`")
private val STRONG = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
private val EMPHASIS = Regex("(?<![*_\\w])([*_])(?=\\S)([^*_\n]+?)(?<=\\S)\\1(?![*_\\w])")
private val LINK = Regex("!?\\[[^\\]\n]*]\\([^)\n]*\\)|<https?://[^>\n]+>|https?://[^\\s)>\"]+")
private val LIST_MARKER = Regex("^\\s*([-*+]|\\d{1,9}[.)])(\\s+\\[[ xX]])?\\s")
private val STRIKE = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")

/** Markdown line by line, as Android Studio's editor colours it. */
private fun highlightMarkdown(text: String, t: CodeTheme): AnnotatedString = buildAnnotatedString {
    append(text)
    var fence: String? = null
    var start = 0
    for (line in text.split('\n')) {
        val end = start + line.length
        val trimmed = line.trimStart()
        when {
            fence != null -> {
                addStyle(t.code, start, end)
                if (trimmed.startsWith(fence!!)) fence = null
            }
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                fence = trimmed.take(3)
                addStyle(t.code, start, end)
            }
            trimmed.startsWith("#") && trimmed.trimStart('#').let { it.isEmpty() || it.startsWith(' ') } -> addStyle(t.heading, start, end)
            trimmed.startsWith(">") -> addStyle(t.quote, start, end)
            line.isNotBlank() && line.all { it == '-' || it == '=' || it == '*' || it == '_' || it == ' ' } && line.count { !it.isWhitespace() } >= 3 ->
                addStyle(t.marker, start, end)
            else -> {
                LIST_MARKER.find(line)?.let { m -> addStyle(t.marker, start + m.range.first, start + m.range.last + 1) }
                if (trimmed.startsWith("|")) {
                    line.forEachIndexed { i, ch -> if (ch == '|') addStyle(t.marker, start + i, start + i + 1) }
                }
                for (m in STRONG.findAll(line)) addStyle(t.strong, start + m.range.first, start + m.range.last + 1)
                for (m in EMPHASIS.findAll(line)) addStyle(t.emphasis, start + m.range.first, start + m.range.last + 1)
                for (m in STRIKE.findAll(line)) addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start + m.range.first, start + m.range.last + 1)
                for (m in LINK.findAll(line)) addStyle(t.link, start + m.range.first, start + m.range.last + 1)
                for (m in INLINE_CODE.findAll(line)) addStyle(t.code, start + m.range.first, start + m.range.last + 1)
            }
        }
        start = end + 1
    }
}
