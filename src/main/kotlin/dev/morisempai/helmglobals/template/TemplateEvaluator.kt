package dev.morisempai.helmglobals.template

/**
 * Evaluates a template expression to the string it renders, so an inline hint can show the result
 * rather than a list of the variables involved:
 * `{{ print .Values.protocol "://" .Values.url "/v1" | quote }}` ⟶ `"https://example/v1"`.
 *
 * A deliberately small subset of the language: string literals, numbers, `.Values` paths,
 * parenthesised sub-expressions, pipes, and the pure functions listed in [apply]. Anything else —
 * `.Release.Name`, a chart's own helper, a function whose result depends on the cluster — makes the
 * whole expression unevaluable and returns `null`, and the caller falls back to showing the raw
 * values. A confidently wrong hint would be worse than a plain one.
 */
object TemplateEvaluator {

    /**
     * [body] is the expression without its `{{ }}`. [resolve] maps a path written after `.Values.`
     * to its value, returning `null` for anything it does not know or that is not a scalar.
     */
    fun evaluate(body: String, resolve: (String) -> String?): String? {
        val tokens = tokenize(body.trim().removePrefix("-").removeSuffix("-")) ?: return null
        if (tokens.isEmpty()) return null
        val parser = Parser(tokens, resolve)
        val value = parser.pipeline() ?: return null
        // Trailing tokens mean the parser stopped early; `failed` means an operand could not be
        // resolved. Either way the value in hand is only part of the expression.
        return if (parser.atEnd && !parser.failed) value else null
    }

    // ---- tokens --------------------------------------------------------------------------------

    private sealed interface Token {
        data class Identifier(val name: String) : Token
        data class Literal(val value: String) : Token
        data class Path(val path: String) : Token
        data object Pipe : Token
        data object Open : Token
        data object Close : Token
    }

    private fun tokenize(text: String): List<Token>? {
        val tokens = ArrayList<Token>()
        var index = 0

        while (index < text.length) {
            val char = text[index]
            when {
                char.isWhitespace() -> index++

                char == '|' -> { tokens += Token.Pipe; index++ }
                char == '(' -> { tokens += Token.Open; index++ }
                char == ')' -> { tokens += Token.Close; index++ }

                char == '"' || char == '`' || char == '\'' -> {
                    val end = endOfLiteral(text, index) ?: return null
                    tokens += Token.Literal(unescape(text.substring(index + 1, end)))
                    index = end + 1
                }

                char == '.' || char == '$' -> {
                    val start = if (char == '$') index + 1 else index
                    if (!text.startsWith(VALUES, start)) return null
                    var end = start + VALUES.length
                    while (end < text.length && isPathChar(text[end])) end++
                    val path = text.substring(start + VALUES.length, end)
                    if (path.isEmpty()) return null
                    tokens += Token.Path(path)
                    index = end
                }

                char.isDigit() || (char == '-' && index + 1 < text.length && text[index + 1].isDigit()) -> {
                    var end = index + 1
                    while (end < text.length && (text[end].isDigit() || text[end] == '.')) end++
                    tokens += Token.Literal(text.substring(index, end))
                    index = end
                }

                char.isLetter() || char == '_' -> {
                    var end = index
                    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
                    tokens += Token.Identifier(text.substring(index, end))
                    index = end
                }

                // Comparison operators, assignments, variables, the bare dot: not modelled.
                else -> return null
            }
        }
        return tokens
    }

    private fun endOfLiteral(text: String, start: Int): Int? {
        val quote = text[start]
        var index = start + 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index += 2
                quote -> return index
                else -> index++
            }
        }
        return null
    }

    private fun unescape(text: String): String =
        text.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")

    private fun isPathChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '.' || char == '_' || char == '-'

    private const val VALUES = ".Values."

    // ---- parser --------------------------------------------------------------------------------

    /** An operand is either a value or the name of the function starting a command. */
    private sealed interface Operand {
        data class Value(val text: String) : Operand
        data class Function(val name: String) : Operand
    }

    private class Parser(private val tokens: List<Token>, private val resolve: (String) -> String?) {
        private var position = 0

        /** Set when an operand was consumed but could not be turned into a value. */
        var failed = false
            private set

        val atEnd: Boolean get() = position >= tokens.size

        /** `command ('|' command)*`, each command receiving the previous result as its last argument. */
        fun pipeline(): String? {
            var value = command(null) ?: return null
            while (peek() == Token.Pipe) {
                position++
                value = command(value) ?: return null
            }
            return value
        }

        private fun command(piped: String?): String? {
            val operands = ArrayList<Operand>()
            while (true) {
                operands += operand() ?: break
            }
            if (operands.isEmpty()) return null

            val first = operands.first()
            if (first !is Operand.Function) {
                // A plain value: it can only stand alone, and nothing may be piped into it.
                if (operands.size > 1 || piped != null) return null
                return (first as Operand.Value).text
            }

            val arguments = operands.drop(1).map {
                when (it) {
                    is Operand.Value -> it.text
                    // A function name in argument position means a call we are not parsing.
                    is Operand.Function -> return null
                }
            }
            return apply(first.name, if (piped == null) arguments else arguments + piped)
        }

        private fun operand(): Operand? {
            val token = peek() ?: return null
            return when (token) {
                is Token.Literal -> { position++; Operand.Value(token.value) }
                is Token.Identifier -> { position++; Operand.Function(token.name) }
                is Token.Path -> {
                    position++
                    val resolved = resolve(token.path)
                    if (resolved == null) failed = true
                    resolved?.let { Operand.Value(it) }
                }
                Token.Open -> {
                    position++
                    val value = pipeline() ?: return null
                    if (peek() != Token.Close) return null
                    position++
                    Operand.Value(value)
                }
                Token.Pipe, Token.Close -> null
            }
        }

        private fun peek(): Token? = tokens.getOrNull(position)
    }

    // ---- functions -----------------------------------------------------------------------------

    /**
     * The pure functions worth modelling. Everything else returns `null` on purpose: `b64enc`,
     * `indent`, `lookup`, `include` and a chart's own helpers cannot be reproduced here, and
     * guessing at them would put a wrong value in front of the reader.
     */
    private fun apply(name: String, arguments: List<String>): String? {
        val value = arguments.lastOrNull()
        val leading = arguments.dropLast(1)

        return when (name) {
            "print" -> arguments.joinToString("")
            "cat" -> arguments.joinToString(" ")
            "printf" -> format(arguments)
            "quote" -> arguments.joinToString(" ") { "\"$it\"" }.takeIf { arguments.isNotEmpty() }
            "squote" -> arguments.joinToString(" ") { "'$it'" }.takeIf { arguments.isNotEmpty() }
            "default" -> if (arguments.size == 2) value!!.ifEmpty { leading[0] } else null
            "upper" -> value?.uppercase().takeIf { arguments.size == 1 }
            "lower" -> value?.lowercase().takeIf { arguments.size == 1 }
            "trim" -> value?.trim().takeIf { arguments.size == 1 }
            "toString" -> value.takeIf { arguments.size == 1 }
            "title" -> value?.split(' ')?.joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }.takeIf { arguments.size == 1 }
            "trimPrefix" -> if (arguments.size == 2) value!!.removePrefix(leading[0]) else null
            "trimSuffix" -> if (arguments.size == 2) value!!.removeSuffix(leading[0]) else null
            "replace" -> if (arguments.size == 3) value!!.replace(leading[0], leading[1]) else null
            else -> null
        }
    }

    /** `%s`, `%v`, `%q` and `%d` against the remaining arguments; any other verb gives up. */
    private fun format(arguments: List<String>): String? {
        val pattern = arguments.firstOrNull() ?: return null
        var next = 1
        val out = StringBuilder()
        var index = 0

        while (index < pattern.length) {
            val char = pattern[index]
            if (char != '%') {
                out.append(char)
                index++
                continue
            }
            val verb = pattern.getOrNull(index + 1) ?: return null
            when (verb) {
                '%' -> out.append('%')
                's', 'v', 'd' -> out.append(arguments.getOrNull(next++) ?: return null)
                'q' -> out.append('"').append(arguments.getOrNull(next++) ?: return null).append('"')
                else -> return null
            }
            index += 2
        }
        return if (next == arguments.size) out.toString() else null
    }
}
