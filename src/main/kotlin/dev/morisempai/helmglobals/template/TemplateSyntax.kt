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
    /** `|` with nothing on one side of it. */
    EMPTY_PIPELINE_STAGE,
    /** `:=` or `=` with nothing to assign. */
    MISSING_ASSIGNED_VALUE,
    /** `if`, `range`, `with`, `define` or `block` with nothing to act on. */
    MISSING_ARGUMENT,
    /** `end`, or `else` that is not `else if`, followed by something. */
    UNEXPECTED_ARGUMENT,
}

data class TemplateProblem(
    val kind: TemplateProblemKind,
    val range: TextRange,
    /** The keyword involved, for the kinds that name one. */
    val keyword: String = "",
)

/**
 * Checks the *structure* of the template expressions in a file: delimiters, quotes, parentheses,
 * pipelines, and the pairing of block actions with their `end`.
 *
 * Deliberately nothing else. An expression this plugin cannot evaluate is not an expression that is
 * wrong — `include`, `b64enc`, `.Release.Name` and a chart's own helpers are all perfectly valid and
 * beyond the evaluator, and an unknown function name might be a helper defined elsewhere. Only
 * breakage that no Helm template could recover from is reported: everything here is something Go's
 * own parser rejects whatever the surrounding chart defines.
 */
object TemplateSyntax {

    private val BLOCK_KEYWORD = Regex("""^(range|if|with|define|block|end|else)\b""")

    /** The keywords that are a parse error without something following them. */
    private val NEEDS_ARGUMENT = setOf("range", "if", "with", "define", "block")

    fun problems(text: CharSequence): List<TemplateProblem> {
        val problems = ArrayList<TemplateProblem>()
        val openers = ArrayDeque<Pair<String, TextRange>>()
        var cursor = 0

        while (true) {
            val start = text.indexOf("{{", cursor)
            reportStrayCloses(text, cursor, if (start < 0) text.length else start, problems)
            if (start < 0) break

            // A comment runs to `*/`, so a `}}` inside it does not end the action. Its contents are
            // free text and are not checked at all.
            if (startsComment(text, start)) {
                val closed = text.indexOf("*/", start + 2)
                val closingDelimiter = if (closed < 0) -1 else text.indexOf("}}", closed)
                if (closingDelimiter < 0) {
                    problems += TemplateProblem(TemplateProblemKind.UNCLOSED, TextRange(start, text.length))
                    break
                }
                cursor = closingDelimiter + 2
                continue
            }

            val end = text.indexOf("}}", start + 2)
            if (end < 0) {
                problems += TemplateProblem(TemplateProblemKind.UNCLOSED, TextRange(start, text.length))
                break
            }

            val region = TextRange(start, end + 2)
            val body = text.substring(start + 2, end)
            if (checkBody(body, region, problems)) {
                trackBlocks(body, region, openers, problems)
            }
            cursor = end + 2
        }

        for ((keyword, region) in openers) {
            problems += TemplateProblem(TemplateProblemKind.MISSING_END, region, keyword)
        }
        return problems
    }

    /** Whether the action at [start] opens a comment, with or without a trim marker before it. */
    private fun startsComment(text: CharSequence, start: Int): Boolean {
        var index = start + 2
        if (index < text.length && text[index] == '-') index++
        while (index < text.length && text[index] == ' ') index++
        return text.startsWith("/*", index)
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

    /** Checks one expression; `false` when it is broken enough that block tracking is meaningless. */
    private fun checkBody(body: String, region: TextRange, problems: MutableList<TemplateProblem>): Boolean {
        val expression = bodyOf(body)
        if (expression.isEmpty()) {
            problems += TemplateProblem(TemplateProblemKind.EMPTY, region)
            return false
        }

        var depth = 0
        var index = 0
        while (index < body.length) {
            when (val char = body[index]) {
                '"', '\'', '`' -> {
                    val closing = closingQuote(body, index, char)
                    if (closing < 0) {
                        problems += TemplateProblem(TemplateProblemKind.UNTERMINATED_STRING, region)
                        return false
                    }
                    index = closing + 1
                }
                '(' -> { depth++; index++ }
                ')' -> {
                    depth--
                    if (depth < 0) {
                        problems += TemplateProblem(TemplateProblemKind.UNBALANCED_PARENTHESES, region)
                        return false
                    }
                    index++
                }
                else -> index++
            }
        }
        if (depth != 0) {
            problems += TemplateProblem(TemplateProblemKind.UNBALANCED_PARENTHESES, region)
            return false
        }

        // Past this point the keyword is still legible, so the block stays tracked: one typo should
        // not also produce a complaint about the `end` that was written correctly.
        val problem = pipelineProblem(expression, region)
            ?: assignmentProblem(expression, region)
            ?: keywordArgumentProblem(expression, region)
        if (problem != null) problems += problem
        return true
    }

    private fun pipelineProblem(expression: String, region: TextRange): TemplateProblem? =
        if (hasEmptyPipelineStage(expression)) {
            TemplateProblem(TemplateProblemKind.EMPTY_PIPELINE_STAGE, region)
        } else {
            null
        }

    private fun assignmentProblem(expression: String, region: TextRange): TemplateProblem? =
        if (ASSIGNMENT_WITHOUT_VALUE.containsMatchIn(withoutStrings(expression))) {
            TemplateProblem(TemplateProblemKind.MISSING_ASSIGNED_VALUE, region)
        } else {
            null
        }

    /**
     * `if`, `range` and friends need something to act on; `end` and a plain `else` take nothing.
     * Both are parse errors in Go regardless of what the chart defines elsewhere.
     */
    private fun keywordArgumentProblem(expression: String, region: TextRange): TemplateProblem? {
        val keyword = BLOCK_KEYWORD.find(expression)?.value ?: return null
        val rest = expression.removePrefix(keyword).trim()

        if (keyword in NEEDS_ARGUMENT) {
            return if (rest.isEmpty()) {
                TemplateProblem(TemplateProblemKind.MISSING_ARGUMENT, region, keyword)
            } else {
                null
            }
        }
        if (rest.isEmpty()) return null
        if (keyword == "end") return TemplateProblem(TemplateProblemKind.UNEXPECTED_ARGUMENT, region, keyword)

        // `else` on its own, or `else if <condition>`.
        if (!rest.startsWith("if")) {
            return TemplateProblem(TemplateProblemKind.UNEXPECTED_ARGUMENT, region, keyword)
        }
        return if (rest.removePrefix("if").isBlank()) {
            TemplateProblem(TemplateProblemKind.MISSING_ARGUMENT, region, "else if")
        } else {
            null
        }
    }

    /** One pipeline: the text collected since the last `|`, and whether a `|` was seen at all. */
    private class Stage {
        val text = StringBuilder()
        var piped = false
    }

    /**
     * A `|` needs an operand on each side. Parentheses open a nested pipeline, so `(x | quote)` is
     * whole while `(| quote)` is not; strings are skipped, so a `|` inside one is just text.
     *
     * Only reached once the parentheses are known to balance, so the stack cannot run dry.
     */
    private fun hasEmptyPipelineStage(expression: String): Boolean {
        val stages = ArrayDeque<Stage>()
        stages.addLast(Stage())
        var index = 0

        while (index < expression.length) {
            val current = stages.last()
            when (val char = expression[index]) {
                '"', '\'', '`' -> {
                    val closing = closingQuote(expression, index, char)
                    if (closing < 0) return false
                    current.text.append(expression, index, closing + 1)
                    index = closing + 1
                }
                '(' -> {
                    current.text.append(char)
                    stages.addLast(Stage())
                    index++
                }
                ')' -> {
                    if (stages.removeLast().isTruncated()) return true
                    index++
                }
                '|' -> {
                    if (current.text.isBlank()) return true
                    current.piped = true
                    current.text.setLength(0)
                    index++
                }
                else -> {
                    current.text.append(char)
                    index++
                }
            }
        }
        return stages.last().isTruncated()
    }

    /** A pipeline that ends on a `|`, with no operand after it. */
    private fun Stage.isTruncated(): Boolean = piped && text.isBlank()

    /** [expression] with the contents of every string literal blanked out. */
    private fun withoutStrings(expression: String): String {
        val out = StringBuilder(expression)
        var index = 0
        while (index < expression.length) {
            val char = expression[index]
            if (char != '"' && char != '\'' && char != '`') {
                index++
                continue
            }
            val closing = closingQuote(expression, index, char)
            if (closing < 0) return out.toString()
            for (blank in index..closing) out.setCharAt(blank, ' ')
            index = closing + 1
        }
        return out.toString()
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

    private val ASSIGNMENT_WITHOUT_VALUE = Regex(""":?=\s*$""")
}
