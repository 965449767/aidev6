package com.aidev.six

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

object SyntaxHighlighter {

    // 热路径用的正则集中编译一次，避免在循环里每字符重新编译（O(n²) 退化）。
    private val RE_JSON_NUMBER = Regex("""-?\b\d+\.?\d*(?:[eE][+-]?\d+)?\b""")
    private val RE_CSS_SELECTOR = Regex("""[.#]?[\w-]+(?=\s*\{)""")
    private val RE_CSS_PROP = Regex("""[\w-]+(?=\s*:)""")
    private val RE_CSS_VALUE = Regex("""(#[\da-fA-F]{3,8}|\b\d+\.?\d*(?:px|em|rem|%|vh|vw|s|ms)?\b)""")
    private val RE_CSS_STRING = Regex(""""[^"]*"|'[^']*'""")
    private val RE_XML_ATTR = Regex("""(\w[\w-]*)\s*=""")

    private val comment = Color(0xFF6A9955)
    private val str = Color(0xFFCE9178)
    private val num = Color(0xFFB5CEA8)
    private val kw = Color(0xFFC586C0)
    private val type = Color(0xFF4EC9B0)
    private val func = Color(0xFFDCDCAA)
    private val annot = Color(0xFFDCDCAA)
    private val prop = Color(0xFF9CDCFE)
    private val plain = Color(0xFFFFFFFF)

    private val kotlin = setOf("val", "var", "fun", "if", "else", "when", "class", "object", "interface",
        "enum", "data", "sealed", "abstract", "open", "override", "private", "protected", "public",
        "internal", "import", "package", "return", "for", "while", "do", "break", "continue",
        "try", "catch", "finally", "throw", "this", "super", "null", "true", "false",
        "is", "as", "in", "typealias", "inline", "suspend", "operator", "infix", "tailrec",
        "companion", "init", "constructor", "by", "get", "set", "field", "value",
        "lateinit", "lazy", "reified", "crossinline", "noinline", "expect", "actual",
        "annotation", "inner", "dynamic")

    private val java = setOf("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
        "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
        "int", "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null")

    private val python = setOf("def", "class", "if", "elif", "else", "for", "while", "break", "continue",
        "return", "import", "from", "as", "try", "except", "finally", "raise", "with", "yield",
        "lambda", "pass", "in", "is", "not", "and", "or", "True", "False", "None",
        "async", "await", "self", "del", "global", "nonlocal", "assert", "match", "case")

    private val shell = setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done",
        "case", "esac", "in", "function", "return", "exit", "break", "continue",
        "export", "local", "source", "alias", "unset", "set", "declare", "typeset",
        "select", "until", "shift", "trap", "exec", "eval", "test", "let")

    private val jsTs = setOf("const", "let", "var", "function", "class", "extends", "import", "export",
        "default", "from", "if", "else", "for", "while", "do", "switch", "case",
        "break", "continue", "return", "throw", "try", "catch", "finally", "new",
        "this", "super", "typeof", "instanceof", "void", "delete", "async", "await",
        "yield", "of", "in", "true", "false", "null", "undefined", "interface",
        "type", "enum", "implements", "abstract", "private", "protected", "public",
        "readonly", "static", "declare", "namespace", "module", "as", "any", "never")

    private val cLike = setOf("int", "long", "short", "char", "float", "double", "void", "struct",
        "union", "enum", "typedef", "const", "static", "extern", "volatile", "register",
        "auto", "signed", "unsigned", "if", "else", "for", "while", "do", "switch",
        "case", "break", "continue", "return", "goto", "sizeof", "true", "false",
        "include", "define", "ifdef", "ifndef", "endif", "pragma", "class", "public",
        "private", "protected", "virtual", "override", "template", "typename", "namespace",
        "using", "friend", "explicit", "mutable", "inline", "noexcept", "constexpr",
        "nullptr", "new", "delete", "try", "catch", "throw", "requires", "concept")

    private val xmlAttrs = setOf("id", "name", "class", "style", "type", "src", "href", "rel",
        "xmlns", "version", "encoding", "layout_width", "layout_height", "text",
        "value", "key", "onClick", "onCreate", "package")

    private val extMap = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "java" to "java",
        "py" to "python",
        "sh" to "shell", "bash" to "shell", "zsh" to "shell",
        "js" to "javascript", "ts" to "typescript", "jsx" to "javascript", "tsx" to "typescript",
        "c" to "c", "h" to "c", "cpp" to "cpp", "hpp" to "cpp", "cc" to "cpp", "cxx" to "cpp",
        "rs" to "rust",
        "xml" to "xml", "html" to "xml", "htm" to "xml", "xhtml" to "xml",
        "json" to "json",
        "yaml" to "yaml", "yml" to "yaml",
        "css" to "css",
        "md" to "markdown",
        "gradle" to "kotlin",
        "swift" to "swift",
        "go" to "go",
        "rb" to "ruby",
        "php" to "php",
    )

    fun highlight(code: String, ext: String): AnnotatedString {
        val lang = extMap[ext.lowercase()] ?: return buildAnnotatedString {
            append(code)
        }
        return highlightLang(code, lang)
    }

    private fun highlightLang(code: String, lang: String): AnnotatedString = buildAnnotatedString {
        append(code)
        val keywords = when (lang) {
            "kotlin" -> kotlin
            "java" -> java
            "python" -> python
            "shell" -> shell
            "javascript", "typescript" -> jsTs
            "c", "cpp", "rust", "go" -> cLike
            else -> null
        }

        if (lang == "xml") {
            highlightXml(code)
            return@buildAnnotatedString
        }
        if (lang == "json") {
            highlightJson(code)
            return@buildAnnotatedString
        }
        if (lang == "yaml") {
            highlightYaml(code)
            return@buildAnnotatedString
        }
        if (lang == "css") {
            highlightCss(code)
            return@buildAnnotatedString
        }
        if (lang == "markdown") {
            highlightMarkdown(code)
            return@buildAnnotatedString
        }

        val commentSingle = when (lang) {
            "shell", "python", "ruby", "php" -> "#"
            "swift" -> "//"
            else -> "//"
        }
        val commentBlock = if (lang in setOf("c", "cpp", "rust", "go", "swift", "kotlin", "java", "javascript", "typescript", "php")) Pair("/*", "*/") else null
        val tripleQuote = if (lang in setOf("kotlin", "python", "swift")) "\"\"\"" else null

        val i = 0
        var pos = 0
        val len = code.length
        val applied = BooleanArray(len)

        while (pos < len) {
            val blockClose = if (commentBlock != null && code.startsWith(commentBlock.second, pos)) {
                pos += commentBlock.second.length
                continue
            } else null

            if (commentBlock != null && code.startsWith(commentBlock.first, pos)) {
                val end = code.indexOf(commentBlock.second, pos + commentBlock.first.length)
                val endPos = if (end >= 0) end + commentBlock.second.length else len
                addStyle(SpanStyle(color = comment), pos, endPos)
                applied.fill(true, pos, endPos)
                pos = endPos
                continue
            }

            if (tripleQuote != null && code.startsWith(tripleQuote, pos)) {
                val searchStart = pos + tripleQuote.length
                val end = code.indexOf(tripleQuote, searchStart)
                val endPos = if (end >= 0) end + tripleQuote.length else len
                addStyle(SpanStyle(color = str), pos, endPos)
                applied.fill(true, pos, endPos)
                pos = endPos
                continue
            }

            val nextSingle = if (commentSingle == "//") code.indexOf("//", pos) else code.indexOf(commentSingle, pos)
            val nextDq = code.indexOf('"', pos)
            val nextSq = if (lang != "shell") code.indexOf('\'', pos) else -1

            val sorted = listOfNotNull(
                if (nextSingle >= 0) Triple(nextSingle, nextSingle + 2, "single") else null,
                if (nextDq >= 0) Triple(nextDq, nextDq + 1, "dq") else null,
                if (nextSq >= 0) Triple(nextSq, nextSq + 1, "sq") else null,
                if (lang == "shell" && code.indexOf('\'', pos) >= 0) Triple(code.indexOf('\'', pos), code.indexOf('\'', pos) + 1, "sq") else null,
            ).sortedBy { it.first }

            val nearest = sorted.firstOrNull() ?: break

            if (nearest.third == "single") {
                val end = code.indexOf('\n', nearest.first)
                val endPos = if (end >= 0) end else len
                addStyle(SpanStyle(color = comment), nearest.first, endPos)
                applied.fill(true, nearest.first, endPos)
                pos = endPos
                continue
            }

            val quote = if (nearest.third == "dq") '"' else '\''
            val end = findStringEnd(code, nearest.first + 1, quote)
            addStyle(SpanStyle(color = str), nearest.first, end)
            applied.fill(true, nearest.first, end)
            pos = end
        }

        highlightKeywords(code, keywords, applied)
        highlightAnnotations(code, applied)
        highlightNumbers(code, applied)
        highlightTypes(code, applied)
        highlightFunctionCalls(code, applied)
    }

    private fun AnnotatedString.Builder.highlightXml(code: String) {
        var pos = 0
        while (pos < code.length) {
            when {
                code.startsWith("<!--", pos) -> {
                    val end = code.indexOf("-->", pos + 4)
                    val e = if (end >= 0) end + 3 else code.length
                    addStyle(SpanStyle(color = comment), pos, e)
                    pos = e
                }
                code[pos] == '<' -> {
                    val end = code.indexOf('>', pos + 1)
                    if (end < 0) break
                    val tag = code.substring(pos, end + 1)
                    if (tag.startsWith("</")) {
                        addStyle(SpanStyle(color = kw), pos, end + 1)
                    } else if (tag.endsWith("/>")) {
                        addStyle(SpanStyle(color = kw), pos, end + 1)
                    } else {
                        val space = tag.indexOf(' ')
                        val tagEnd = if (space > 0) space else tag.length - 1
                        addStyle(SpanStyle(color = kw), pos, pos + tagEnd)

                        val attrPattern = RE_XML_ATTR
                        for (m in attrPattern.findAll(tag)) {
                            val abs = pos + m.range.first
                            addStyle(SpanStyle(color = prop), abs, abs + m.value.indexOf('=').let { if (it > 0) it else m.value.length })
                        }

                        val valPattern = Regex("""("[^"]*"|'[^']*')""")
                        for (m in valPattern.findAll(tag)) {
                            val abs = pos + m.range.first
                            addStyle(SpanStyle(color = str), abs, abs + m.value.length)
                        }

                        if (tag.endsWith(">")) {
                            addStyle(SpanStyle(color = kw), pos + tag.length - 1, end + 1)
                        } else if (tag.endsWith("/>")) {
                            val slash = tag.lastIndexOf('/')
                            addStyle(SpanStyle(color = kw), pos + slash, end + 1)
                        }
                    }
                    pos = end + 1
                }
                code[pos] == '&' -> {
                    val end = code.indexOf(';', pos)
                    if (end > 0) {
                        addStyle(SpanStyle(color = prop), pos, end + 1)
                        pos = end + 1
                    } else { pos++ }
                }
                else -> pos++
            }
        }
    }

    private fun AnnotatedString.Builder.highlightJson(code: String) {
        var pos = 0
        while (pos < code.length) {
            when {
                code[pos] == '"' -> {
                    val end = findStringEnd(code, pos + 1, '"')
                    val inner = if (end > pos + 1) code.substring(pos + 1, end - 1) else ""
                    val part = code.substring(pos.coerceAtLeast(0), end)
                    val isKey = part.contains(":") || inner.all { it == ' ' || it == '_' || it == '$' || it.isLetterOrDigit() }
                    val isAfterColon = pos > 0 && code.substring(0, pos).trimEnd().lastOrNull() == ':'
                    addStyle(SpanStyle(color = if (isKey) prop else str), pos, end)
                    pos = end
                }
                code[pos] in "{}[]" -> { pos++ }
                code[pos] == ',' || code[pos] == ':' -> { pos++ }
                code[pos] == '/' && pos + 1 < code.length && code[pos + 1] == '/' -> {
                    val end = code.indexOf('\n', pos)
                    val e = if (end >= 0) end else code.length
                    addStyle(SpanStyle(color = comment), pos, e)
                    pos = e
                }
                code[pos] == '/' && pos + 1 < code.length && code[pos + 1] == '*' -> {
                    val end = code.indexOf("*/", pos + 2)
                    val e = if (end >= 0) end + 2 else code.length
                    addStyle(SpanStyle(color = comment), pos, e)
                    pos = e
                }
                code[pos] == '-' || code[pos].isDigit() -> {
                    val m = RE_JSON_NUMBER.find(code, pos)
                    if (m != null && m.range.first == pos) {
                        addStyle(SpanStyle(color = num), m.range.first, m.range.last + 1)
                        pos = m.range.last + 1
                    } else pos++
                }
                code.startsWith("true", pos) || code.startsWith("false", pos) || code.startsWith("null", pos) -> {
                    val kwLen = when { code.startsWith("true", pos) -> 4; code.startsWith("false", pos) -> 5; else -> 4 }
                    addStyle(SpanStyle(color = kw), pos, pos + kwLen)
                    pos += kwLen
                }
                else -> pos++
            }
        }
    }

    private fun AnnotatedString.Builder.highlightYaml(code: String) {
        val lines = code.split("\n")
        var offset = 0
        for (line in lines) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("#") -> {
                    addStyle(SpanStyle(color = comment), offset, offset + line.length)
                }
                trimmed.contains(": ") || trimmed.endsWith(":") -> {
                    val colon = line.indexOf(':')
                    if (colon >= 0) {
                        addStyle(SpanStyle(color = prop), offset, offset + colon)
                        val rest = line.substring(colon + 1)
                        val valStart = offset + colon + 1
                        val vTrimmed = rest.trimStart()
                        if (vTrimmed.startsWith('"') || vTrimmed.startsWith('\'')) {
                            val quote = vTrimmed[0]
                            val end = findStringEnd(code, valStart + rest.indexOf(vTrimmed), quote)
                            addStyle(SpanStyle(color = str), valStart + rest.indexOf(vTrimmed), end)
                        }
                    }
                }
                trimmed.startsWith("- ") -> {
                    addStyle(SpanStyle(color = kw), offset + line.indexOf("- "), offset + line.indexOf("- ") + 2)
                }
                trimmed.startsWith('"') || trimmed.startsWith('\'') -> {
                    val quote = trimmed[0]
                    val start = offset + line.indexOf(quote)
                    val end = findStringEnd(code, start + 1, quote)
                    addStyle(SpanStyle(color = str), start, end)
                }
            }
            offset += line.length + 1
        }
    }

    private fun AnnotatedString.Builder.highlightCss(code: String) {
        var pos = 0
        while (pos < code.length) {
            when {
                code.startsWith("/*", pos) -> {
                    val end = code.indexOf("*/", pos + 2)
                    val e = if (end >= 0) end + 2 else code.length
                    addStyle(SpanStyle(color = comment), pos, e)
                    pos = e
                }
                code[pos] == '{' || code[pos] == '}' -> { pos++ }
                code[pos] == '/' && pos + 1 < code.length && code[pos + 1] == '/' -> {
                    val end = code.indexOf('\n', pos)
                    val e = if (end >= 0) end else code.length
                    addStyle(SpanStyle(color = comment), pos, e)
                    pos = e
                }
                else -> {
                    val selectorMatch = RE_CSS_SELECTOR.find(code, pos)
                    if (selectorMatch != null && selectorMatch.range.first == pos) {
                        addStyle(SpanStyle(color = type), selectorMatch.range.first, selectorMatch.range.last + 1)
                        pos = selectorMatch.range.last + 1
                        continue
                    }
                    val propMatch = RE_CSS_PROP.find(code, pos)
                    if (propMatch != null && propMatch.range.first == pos) {
                        addStyle(SpanStyle(color = prop), propMatch.range.first, propMatch.range.last + 1)
                        pos = propMatch.range.last + 1
                        continue
                    }
                    val valMatch = RE_CSS_VALUE.find(code, pos)
                    if (valMatch != null && valMatch.range.first == pos) {
                        addStyle(SpanStyle(color = num), valMatch.range.first, valMatch.range.last + 1)
                        pos = valMatch.range.last + 1
                        continue
                    }
                    val strMatch = RE_CSS_STRING.find(code, pos)
                    if (strMatch != null && strMatch.range.first == pos) {
                        addStyle(SpanStyle(color = str), strMatch.range.first, strMatch.range.last + 1)
                        pos = strMatch.range.last + 1
                        continue
                    }
                    pos++
                }
            }
        }
    }

    private fun AnnotatedString.Builder.highlightMarkdown(code: String) {
        var pos = 0
        while (pos < code.length) {
            when {
                code[pos] == '#' && (pos == 0 || code[pos - 1] == '\n') -> {
                    val end = code.indexOf('\n', pos)
                    val e = if (end >= 0) end else code.length
                    addStyle(SpanStyle(color = kw, fontWeight = FontWeight.Bold), pos, e)
                    pos = e
                }
                code.startsWith("```", pos) -> {
                    val end = code.indexOf("```", pos + 3)
                    val e = if (end >= 0) end + 3 else code.length
                    addStyle(SpanStyle(color = comment), pos, e)
                    pos = e
                }
                code.startsWith("`", pos) -> {
                    val end = code.indexOf('`', pos + 1)
                    if (end > 0) {
                        addStyle(SpanStyle(color = str, fontStyle = FontStyle.Italic), pos, end + 1)
                        pos = end + 1
                    } else pos++
                }
                code.startsWith("**", pos) || code.startsWith("__", pos) -> {
                    val delim = code.substring(pos, pos + 2)
                    val end = code.indexOf(delim, pos + 2)
                    if (end > 0) {
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), pos, end + 2)
                        pos = end + 2
                    } else pos++
                }
                code.startsWith("*", pos) && !code.startsWith("**", pos) && (pos == 0 || code[pos - 1] == ' ' || code[pos - 1] == '\n') -> {
                    val end = code.indexOf('*', pos + 1)
                    if (end > 0) {
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), pos, end + 1)
                        pos = end + 1
                    } else pos++
                }
                code.startsWith("[", pos) -> {
                    val closeB = code.indexOf(']', pos + 1)
                    val openP = if (closeB > 0) code.indexOf('(', closeB + 1) else -1
                    val closeP = if (openP > 0) code.indexOf(')', openP + 1) else -1
                    if (closeB > 0 && openP > 0 && closeP > 0) {
                        addStyle(SpanStyle(color = prop, fontWeight = FontWeight.Bold), pos + 1, closeB)
                        addStyle(SpanStyle(color = comment, fontStyle = FontStyle.Italic), openP, closeP + 1)
                        pos = closeP + 1
                    } else pos++
                }
                code.startsWith("- ", pos) || code.startsWith("* ", pos) -> {
                    addStyle(SpanStyle(color = kw), pos, pos + 2)
                    pos += 2
                }
                code.startsWith("---", pos) || code.startsWith("___", pos) || code.startsWith("***", pos) -> {
                    val end = code.indexOf('\n', pos)
                    val e = if (end >= 0) end else code.length
                    addStyle(SpanStyle(color = comment), pos, e)
                    pos = e
                }
                else -> pos++
            }
        }
    }

    private fun AnnotatedString.Builder.highlightKeywords(code: String, keywords: Set<String>?, applied: BooleanArray?) {
        if (keywords == null) return
        val wordPattern = Regex("""\b([A-Za-z_]\w*)\b""")
        for (m in wordPattern.findAll(code)) {
            val word = m.value
            if (word in keywords && !isApplied(applied, m.range.first, m.range.last + 1)) {
                addStyle(SpanStyle(color = kw, fontWeight = FontWeight.Bold), m.range.first, m.range.last + 1)
            }
        }
    }

    private fun AnnotatedString.Builder.highlightAnnotations(code: String, applied: BooleanArray?) {
        val annotPattern = Regex("""@(\w+(?:\.\w+)*)""")
        for (m in annotPattern.findAll(code)) {
            if (!isApplied(applied, m.range.first, m.range.last + 1)) {
                addStyle(SpanStyle(color = annot), m.range.first, m.range.last + 1)
            }
        }
    }

    private fun AnnotatedString.Builder.highlightNumbers(code: String, applied: BooleanArray?) {
        val numPattern = Regex("""\b(\d+\.?\d*[fFLl]?|0[xX][\da-fA-F]+[lL]?)\b""")
        for (m in numPattern.findAll(code)) {
            if (!isApplied(applied, m.range.first, m.range.last + 1)) {
                addStyle(SpanStyle(color = num), m.range.first, m.range.last + 1)
            }
        }
    }

    private fun AnnotatedString.Builder.highlightTypes(code: String, applied: BooleanArray?) {
        val typePattern = Regex("""\b([A-Z]\w*)\b""")
        for (m in typePattern.findAll(code)) {
            if (!isApplied(applied, m.range.first, m.range.last + 1)) {
                val word = m.value
                if (word.length > 1 && word.all { it.isLetterOrDigit() || it == '_' }) {
                    addStyle(SpanStyle(color = type), m.range.first, m.range.last + 1)
                }
            }
        }
    }

    private fun AnnotatedString.Builder.highlightFunctionCalls(code: String, applied: BooleanArray?) {
        val funcPattern = Regex("""\b([a-zA-Z_]\w*)\s*\(""")
        for (m in funcPattern.findAll(code)) {
            val name = m.groupValues[1]
            if (name.length > 1 && name.none { it.isUpperCase() } && name != "if" && name != "for" && name != "while" && name != "when" && name != "switch" && name != "catch") {
                if (!isApplied(applied, m.range.first, m.range.first + name.length)) {
                    addStyle(SpanStyle(color = func), m.range.first, m.range.first + name.length)
                }
            }
        }
    }

    private fun findStringEnd(code: String, start: Int, quote: Char): Int {
        var i = start
        while (i < code.length) {
            when (code[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return code.length
    }

    private fun isApplied(applied: BooleanArray?, start: Int, end: Int): Boolean {
        if (applied == null) return false
        for (i in start until end.coerceAtMost(applied.size)) {
            if (applied[i]) return true
        }
        return false
    }
}
