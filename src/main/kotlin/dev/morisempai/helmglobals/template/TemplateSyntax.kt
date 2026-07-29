package dev.morisempai.helmglobals.template

import com.intellij.openapi.util.TextRange

enum class TemplateProblemKind {
    /** `{{` with no `}}` after it. */
    UNCLOSED,
    /** `}}` with no `{{` before it. */
    STRAY_CLOSE,
    /** `{{ }}` with nothing to render. */
    EMPTY,
    UNBALANCED_PARENTHESES,
    UNTERMINATED_STRING,
    /** `end` closing nothing. */
    UNEXPECTED_END,
    /** `else` outside a conditional or a loop. */
    UNEXPECTED_ELSE,
    /** `range`, `if`, `with`, `define` or `block` that is never closed. */
    MISSING_END,
}

data class TemplateProblem(
    val kind: TemplateProblemKind,
    val range: TextRange,
    /** The keyword involved, for the kinds that name one. */
    val keyword: String = "",
)

/**
 * Checks the *structure* of the template expressions in a file: delimiters, quotes, parentheses and
 * the pairing of block actions with their `end`.
 *
 * Deliberately nothing else. An expression this plugin cannot evaluate is not an expression that is
 * wrong — `include`, `b64enc`, `.Release.Name` and a chart's own helpers are all perfectly valid and
 * beyond the evaluator, and an unknown function name might be a helper defined elsewhere. Only
 * breakage that no Helm template could recover from is reported.
 */
object TemplateSyntax {

    private val BLOCK_KEYWORD = Regex("""^(range|if|with|define|block|end|else)\b""")

    fun problems(text: CharSequence): List<TemplateProblem> {
        val problems = ArrayList<TemplateProblem>()
        val openers = ArrayDeque<Pair<String, TextRange>>()
        var cursor = 0

        while (true) {
            val start = text.indexOf("{{", cursor)
            reportStrayCloses(text, cursor, if (start < 0) text.length else start, problems)
            if (start < 0) break

            val end = text.indexOf("}}", start + 2)
            if (end < 0) {
                problems += TemplateProblem(TemplateProblemKind.UNCLOSED, TextRange(start, text.length))
                break
            }

            val region = TextRange(start, end + 2)
            val body = text.substring(start + 2, end)
            checkBody(body, region, problems)
            trackBlocks(body, region, openers, problems)
            cursor = end + 2
        }

        for ((keyword, region) in openers) {
            problems += TemplateProblem(TemplateProblemKind.MISSING_END, region, keyword)
        }
        return problems
    }

    private fun reportStrayCloses(
        text: CharSequence,
        from: Int,
        until: Int,
        problems: MutableList<TemplateProblem>,
    ) {
        var index = from
        while (index < until) {
            val stray = text.indexOf("}}", index)
            if (stray < 0 || stray >= until) return
            problems += TemplateProblem(TemplateProblemKind.STRAY_CLOSE, TextRange(stray, stray + 2))
            index = stray + 2
        }
    }

    private fun checkBody(body: String, region: TextRange, problems: MutableList<TemplateProblem>) {
        if (bodyOf(body).isEmpty()) {
            problems += TemplateProblem(TemplateProblemKind.EMPTY, region)
            return
        }

        var depth = 0
        var index = 0
        while (index < body.length) {
            when (val char = body[index]) {
                '"', '\'', '`' -> {
                    val closing = closingQuote(body, index, char)
                    if (closing < 0) {
                        problems += TemplateProblem(TemplateProblemKind.UNTERMINATED_STRING, region)
                        return
                    }
                    index = closing + 1
                }
                '(' -> { depth++; index++ }
                ')' -> {
                    depth--
                    if (depth < 0) {
                        problems += TemplateProblem(TemplateProblemKind.UNBALANCED_PARENTHESES, region)
                        return
                    }
                    index++
                }
                else -> index++
            }
        }
        if (depth != 0) problems += TemplateProblem(TemplateProblemKind.UNBALANCED_PARENTHESES, region)
    }

    private fun trackBlocks(
        body: String,
        region: TextRange,
        openers: ArrayDeque<Pair<String, TextRange>>,
        problems: MutableList<TemplateProblem>,
    ) {
        when (val keyword = BLOCK_KEYWORD.find(bodyOf(body))?.value) {
            null -> Unit
            "end" -> if (openers.removeLastOrNull() == null) {
                problems += TemplateProblem(TemplateProblemKind.UNEXPECTED_END, region)
            }
            // `else` neither opens nor closes; it just has to sit inside something.
            "else" -> if (openers.isEmpty()) {
                problems += TemplateProblem(TemplateProblemKind.UNEXPECTED_ELSE, region)
            }
            else -> openers.addLast(keyword to region)
        }
    }

    /** The expression without its whitespace-trim markers. */
    private fun bodyOf(body: String): String =
        body.trim().removePrefix("-").removeSuffix("-").trim()

    private fun closingQuote(body: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < body.length) {
            when (body[index]) {
                '\\' -> index += 2
                quote -> return index
                else -> index++
            }
        }
        return -1
    }
}
