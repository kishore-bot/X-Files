package com.xfiles.documents.code

enum class FoldKind { Block, Comment, Imports }

/**
 * A foldable region, as in an IDE: [start] stays visible with a placeholder; lines
 * `start + 1 .. lastHidden` are hidden when collapsed. [lastHidden] is [end] when the closing line
 * has nothing after its bracket, else `end - 1`, so `} else {` stays visible.
 */
data class CodeFold(val start: Int, val end: Int, val lastHidden: Int, val kind: FoldKind)

/**
 * IntelliJ-style folding without a parser: brackets outside strings and comments for C-like
 * languages, indentation for Python and YAML, plus block comments, docstrings and import groups.
 */
object CodeFolding {
    private val indentLanguages = setOf("py", "pyw", "pyi", "yaml", "yml", "nim", "coffee")
    private val hashComments = setOf(
        "py", "pyw", "pyi", "rb", "sh", "bash", "zsh", "fish", "ps1", "yaml", "yml", "toml", "r", "pl", "ex", "exs",
        "jl", "nim", "conf", "cfg", "ini", "properties", "env", "tf", "coffee",
    )
    private val dashComments = setOf("lua", "sql", "hs")
    /** Where ' starts a string rather than a one-character literal. */
    private val quoteStrings = setOf(
        "py", "pyw", "pyi", "rb", "sh", "bash", "zsh", "fish", "js", "mjs", "cjs", "ts", "tsx", "jsx", "php", "lua", "sql", "r",
        "pl", "yaml", "yml", "dart", "css", "scss", "sass", "less", "vue", "svelte", "coffee", "nim", "ex", "exs", "jl", "toml",
    )
    private val tripleQuotes = setOf("py", "pyw", "pyi", "kt", "kts", "scala", "swift", "java", "dart", "gradle", "groovy", "jl", "toml")
    private val backtickStrings = setOf("js", "mjs", "cjs", "ts", "tsx", "jsx", "go", "vue", "svelte")
    private val importLine = Regex("""^(import\s|from\s+\S+\s+import\s|#include\s*[<"]|#import\s|using\s+[\w.]+\s*;|use\s+[\w:{}, *]+;?|package\s+[\w.]+;?$|require(_once)?\b|const\s+\w+\s*=\s*require\()""")

    fun regions(lines: List<String>, extension: String?): List<CodeFold> {
        val ext = extension?.lowercase().orEmpty()
        val folds = ArrayList<CodeFold>()
        val indent = ext in indentLanguages
        scan(lines, ext, folds, braces = !indent)
        if (indent) indentBlocks(lines, ext, folds)
        importGroups(lines, folds)
        // One fold per start line: the outermost.
        return folds.groupBy { it.start }.map { (_, same) -> same.maxBy { it.end } }.sortedBy { it.start }
    }

    /** Placeholder after the visible start line of a collapsed fold, e.g. "…}" or " …" (closing a comment). */
    fun placeholder(fold: CodeFold, lines: List<String>): String = when (fold.kind) {
        FoldKind.Imports -> " …"
        FoldKind.Comment -> if ("/*" in lines[fold.start]) " … */" else " …"
        FoldKind.Block -> if (fold.lastHidden == fold.end) "…" + lines[fold.end].trim() else " …"
    }

    private enum class Mode { Code, BlockComment, Triple }

    /** Brackets and block comments, skipping strings and comments. */
    private fun scan(lines: List<String>, ext: String, folds: MutableList<CodeFold>, braces: Boolean) {
        val hash = ext in hashComments
        val dash = ext in dashComments
        val slashes = !hash || ext == "tf" || ext == "php"
        val quoteString = ext in quoteStrings
        val triple = ext in tripleQuotes
        val backtick = ext in backtickStrings
        val stack = ArrayList<Pair<Int, Int>>() // line, column of '{'
        var mode = Mode.Code
        var tripleQuote = "\"\"\""
        var commentStart = 0
        var tripleStart = 0
        for ((i, line) in lines.withIndex()) {
            var c = 0
            while (c < line.length) {
                val ch = line[c]
                when (mode) {
                    Mode.BlockComment -> {
                        if (ch == '*' && line.getOrNull(c + 1) == '/') {
                            mode = Mode.Code
                            if (i > commentStart) folds += CodeFold(commentStart, i, if (rest(line, c + 2)) i - 1 else i, FoldKind.Comment)
                            c++
                        }
                    }
                    Mode.Triple -> if (line.startsWith(tripleQuote, c)) {
                        mode = Mode.Code
                        if (i > tripleStart && lines[tripleStart].trimStart().startsWith(tripleQuote)) {
                            folds += CodeFold(tripleStart, i, i, FoldKind.Comment)
                        }
                        c += 2
                    }
                    Mode.Code -> when {
                        slashes && ch == '/' && line.getOrNull(c + 1) == '/' -> c = line.length
                        hash && ch == '#' -> c = line.length
                        dash && ch == '-' && line.getOrNull(c + 1) == '-' -> c = line.length
                        slashes && ch == '/' && line.getOrNull(c + 1) == '*' -> { mode = Mode.BlockComment; commentStart = i; c++ }
                        triple && (line.startsWith("\"\"\"", c) || (ext.startsWith("py") && line.startsWith("'''", c))) -> {
                            tripleQuote = line.substring(c, c + 3)
                            val close = line.indexOf(tripleQuote, c + 3)
                            if (close >= 0) c = close + 2 else { mode = Mode.Triple; tripleStart = i; c = line.length }
                        }
                        ch == '"' || (backtick && ch == '`') -> c = endOfString(line, c, ch)
                        ch == '\'' -> c = if (quoteString) endOfString(line, c, '\'') else endOfCharLiteral(line, c)
                        braces && ch == '{' -> stack += i to c
                        braces && ch == '}' -> stack.removeLastOrNull()?.let { (start, _) ->
                            if (i > start) {
                                val lastHidden = if (rest(line, c + 1)) i - 1 else i
                                if (lastHidden > start) folds += CodeFold(start, i, lastHidden, FoldKind.Block)
                            }
                        }
                    }
                }
                c++
            }
        }
    }

    /** Whether real code follows on the line (not just `)`, `;`, `,` or `]`). */
    private fun rest(line: String, from: Int): Boolean = line.substring(from.coerceAtMost(line.length)).any { it !in " \t);,]" }

    private fun endOfString(line: String, start: Int, quote: Char): Int {
        var c = start + 1
        while (c < line.length) {
            if (line[c] == '\\') c++ else if (line[c] == quote) return c
            c++
        }
        return line.length
    }

    /** 'a' or '\n' is a character; otherwise (a Rust lifetime, an apostrophe) the quote is plain. */
    private fun endOfCharLiteral(line: String, start: Int): Int = when {
        line.getOrNull(start + 1) == '\\' -> line.indexOf('\'', start + 2).takeIf { it in start + 3..start + 8 } ?: start
        line.getOrNull(start + 2) == '\'' -> start + 2
        else -> start
    }

    /** Python/YAML: a line followed by more-indented lines folds them. */
    private fun indentBlocks(lines: List<String>, ext: String, folds: MutableList<CodeFold>) {
        fun indentOf(line: String) = line.length - line.trimStart().length
        val python = ext.startsWith("py")
        for (i in lines.indices) {
            val header = lines[i]
            if (header.isBlank()) continue
            val code = header.substringBefore('#').trimEnd()
            if (python && !(code.endsWith(':') || code.endsWith('(') || code.endsWith('[') || code.endsWith('{') || code.endsWith(','))) continue
            val level = indentOf(header)
            var end = i
            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || indentOf(lines[j]) > level)) {
                if (lines[j].isNotBlank()) end = j
                j++
            }
            // A closing bracket at the header's own level ends a multi-line literal: keep it visible.
            if (end > i) folds += CodeFold(i, end, end, FoldKind.Block)
        }
    }

    /** Two or more import lines in a row (blank lines allowed between) fold together. */
    private fun importGroups(lines: List<String>, folds: MutableList<CodeFold>) {
        var first = -1
        var last = -1
        fun close() {
            if (first >= 0 && last > first) folds += CodeFold(first, last, last, FoldKind.Imports)
            first = -1
        }
        for ((i, line) in lines.withIndex()) {
            val text = line.trim()
            when {
                importLine.containsMatchIn(text) && !text.startsWith("package") -> { if (first < 0) first = i; last = i }
                text.isEmpty() -> {}
                else -> close()
            }
        }
        close()
    }
}
