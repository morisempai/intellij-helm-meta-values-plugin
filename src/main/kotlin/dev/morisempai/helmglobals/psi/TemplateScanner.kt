package dev.morisempai.helmglobals.psi

import com.intellij.openapi.util.TextRange

/**
 * A single `.Values.<path>` reference found in a template expression.
 * All ranges are relative to the text that was passed to the scanner.
 */
data class ValuesReference(
    /** Dotted path as written after `.Values.`, e.g. `global.image.registry`. */
    val path: String,
    /** Range covering the whole path. */
    val pathRange: TextRange,
    /** Range of each dotted segment, in order; `segmentRanges.size == path.split('.').size`. */
    val segmentRanges: List<TextRange>,
    /** Range of the enclosing expression within the scanned text. */
    val templateRange: TextRange,
    /** Body of the enclosing expression; used to spot `toYaml`/`range`/... usages. */
    val templateBody: String,
) {
    /** Cumulative path up to and including segment [index], e.g. `global.image` for index 1. */
    fun pathUpTo(index: Int): String = path.split('.').take(index + 1).joinToString(".")

    fun isUnder(root: String): Boolean = path == root || path.startsWith("$root.")

    /**
     * `true` when the enclosing expression consumes the value as a structure rather than as a
     * scalar, in which case pointing at a mapping is perfectly legitimate.
     */
    val usesValueAsStructure: Boolean
        get() = TemplateScanner.mentionsStructureFunction(templateBody)
}

object TemplateScanner {

    private val TEMPLATE_REGION = Regex("""\{\{.*?}}""", RegexOption.DOT_MATCHES_ALL)

    /** `.Values.a.b.c`, optionally prefixed with `$` as in `{{ $.Values.global.x }}`. */
    private val VALUES_REFERENCE = Regex("""\$?\.Values((?:\.[A-Za-z0-9_-]+)+)""")

    /** Template functions that legitimately consume a mapping rather than a scalar. */
    private val STRUCTURE_FUNCTION = Regex(
        """\b(toYaml|toJson|toToml|range|with|if|index|keys|deepCopy|merge|mergeOverwrite|dig|nindent|include|tpl)\b"""
    )

    /**
     * `true` when the expression body calls a function that takes a structure. The `.Values.…`
     * paths are blanked out first, so a variable called `global.range` is not mistaken for the
     * `range` action.
     */
    fun mentionsStructureFunction(templateBody: String): Boolean =
        STRUCTURE_FUNCTION.containsMatchIn(VALUES_REFERENCE.replace(templateBody, " "))

    /** Scans text that still carries its `{{ ... }}` delimiters, e.g. a quoted YAML scalar. */
    fun scan(text: CharSequence): List<ValuesReference> {
        if (text.length < MIN_DELIMITED_LENGTH || !text.contains("{{")) return emptyList()

        val result = ArrayList<ValuesReference>()
        for (region in TEMPLATE_REGION.findAll(text)) {
            collectInto(
                result,
                haystack = region.value,
                offsetInText = region.range.first,
                templateRange = TextRange(region.range.first, region.range.last + 1),
                templateBody = region.value.removeSurrounding("{{", "}}"),
            )
        }
        return result
    }

    /**
     * Scans text that is already the *body* of a template expression, with the braces stripped by
     * the YAML parser. See [HelmTemplates] for when this applies.
     */
    fun scanBody(text: CharSequence): List<ValuesReference> {
        if (!text.contains(".Values.")) return emptyList()

        val result = ArrayList<ValuesReference>()
        collectInto(
            result,
            haystack = text,
            offsetInText = 0,
            templateRange = TextRange(0, text.length),
            templateBody = text.toString(),
        )
        return result
    }

    private fun collectInto(
        sink: MutableList<ValuesReference>,
        haystack: CharSequence,
        offsetInText: Int,
        templateRange: TextRange,
        templateBody: String,
    ) {
        for (match in VALUES_REFERENCE.findAll(haystack)) {
            val group = match.groups[1] ?: continue
            // group.value starts with the '.' separating `.Values` from the path.
            val pathStart = offsetInText + group.range.first + 1
            val path = group.value.substring(1)

            val segmentRanges = ArrayList<TextRange>(path.count { it == '.' } + 1)
            var offset = pathStart
            for (segment in path.split('.')) {
                segmentRanges += TextRange(offset, offset + segment.length)
                offset += segment.length + 1
            }

            sink += ValuesReference(
                path = path,
                pathRange = TextRange(pathStart, pathStart + path.length),
                segmentRanges = segmentRanges,
                templateRange = templateRange,
                templateBody = templateBody,
            )
        }
    }

    /** Length of the shortest string that could possibly contain a delimited match: `{{.Values.x}}`. */
    private const val MIN_DELIMITED_LENGTH = 13
}
