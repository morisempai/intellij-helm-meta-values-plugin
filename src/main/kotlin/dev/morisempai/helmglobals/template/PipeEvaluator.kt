package dev.morisempai.helmglobals.template

/**
 * Applies a pipe chain to a value read out of the meta file, so an inline hint can show what the
 * expression really produces: `{{ .Values.global.registry | quote }}` renders
 * `= "registry.dev.corp"`, not `= registry.dev.corp`.
 *
 * This models the handful of pure, argument-free string functions plus `default`. Anything else —
 * `printf`, a chart's own helper, a function taking the value as a non-final argument — returns
 * `null`, and the caller falls back to showing the raw value. Guessing would be worse than not
 * showing the transformation at all.
 */
object PipeEvaluator {

    /**
     * [templateBody] is the expression without its braces, [path] the dotted path whose resolved
     * [value] is being displayed. Returns `null` when the expression is not simply that variable
     * followed by pipes this object understands.
     */
    fun apply(templateBody: String, path: String, value: String): String? {
        val marker = ".Values.$path"
        val start = templateBody.indexOf(marker)
        if (start < 0) return null

        // `.Values.global.registry` must not match a request for `.Values.global.reg`.
        val after = start + marker.length
        if (after < templateBody.length && isPathChar(templateBody[after])) return null

        // Anything before the variable (a function taking it as an argument, say) is not a pipe.
        val head = templateBody.take(start).trim().removeSuffix("$")
        if (head.isNotEmpty() && head != "-") return null

        val rest = templateBody.substring(after).trim().removeSuffix("-").trim()
        if (rest.isEmpty()) return value

        val segments = rest.split('|')
        // The text between the variable and the first pipe must be nothing at all.
        if (segments.first().isNotBlank()) return null

        var current = value
        for (segment in segments.drop(1)) {
            current = applyOne(segment.trim(), current) ?: return null
        }
        return current
    }

    private fun applyOne(call: String, value: String): String? {
        val tokens = tokenize(call) ?: return null
        val name = tokens.firstOrNull() ?: return null
        val arguments = tokens.drop(1)

        return when {
            name == "default" && arguments.size == 1 -> value.ifEmpty { arguments[0] }
            name == "quote" && arguments.isEmpty() -> "\"$value\""
            name == "squote" && arguments.isEmpty() -> "'$value'"
            name == "upper" && arguments.isEmpty() -> value.uppercase()
            name == "lower" && arguments.isEmpty() -> value.lowercase()
            name == "trim" && arguments.isEmpty() -> value.trim()
            name == "toString" && arguments.isEmpty() -> value
            name == "trimPrefix" && arguments.size == 1 -> value.removePrefix(arguments[0])
            name == "trimSuffix" && arguments.size == 1 -> value.removeSuffix(arguments[0])
            name == "title" && arguments.isEmpty() -> value.split(' ').joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }
            else -> null
        }
    }

    /**
     * Splits a call into its name and arguments, unquoting string literals. Returns `null` for
     * anything with structure this object does not model, such as a nested `(...)` call.
     */
    private fun tokenize(call: String): List<String>? {
        if (call.isEmpty() || call.contains('(') || call.contains('$')) return null

        val tokens = ArrayList<String>()
        var index = 0
        while (index < call.length) {
            val char = call[index]
            when {
                char.isWhitespace() -> index++
                char == '"' || char == '\'' -> {
                    val end = call.indexOf(char, index + 1)
                    if (end < 0) return null
                    tokens += call.substring(index + 1, end)
                    index = end + 1
                }
                else -> {
                    var end = index
                    while (end < call.length && !call[end].isWhitespace()) end++
                    tokens += call.substring(index, end)
                    index = end
                }
            }
        }
        return tokens
    }

    private fun isPathChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '.' || char == '_' || char == '-'
}
