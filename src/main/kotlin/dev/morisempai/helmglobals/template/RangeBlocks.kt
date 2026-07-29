package dev.morisempai.helmglobals.template

import com.intellij.openapi.util.TextRange
import dev.morisempai.helmglobals.psi.TemplateScanner

/**
 * A `{{ range .Values.<path> }} … {{ end }}` block found in a values file.
 */
data class RangeBlock(
    /** Path written after `.Values.` in the range expression. */
    val path: String,
    /** Text between the range expression and the closing `end`. */
    val body: TextRange,
    /** Range of the `{{ end }}` expression, where a preview of the loop is anchored. */
    val end: TextRange,
    /** Variable bound to the element, as in `range $host := …` — without the `$`. */
    val elementVariable: String? = null,
    /** Variable bound to the position, as in `range $i, $host := …`. */
    val indexVariable: String? = null,
)

/**
 * Finds range blocks by walking the `{{ … }}` regions in document order and matching each block
 * opener to its `end`. Openers other than `range` are tracked too, so a nested `if` cannot make a
 * range close early.
 */
object RangeBlocks {

    /**
     * `range .Values.x`, `range $host := .Values.x`, `range $i, $host := .Values.x`. With one
     * variable Go binds the element, with two the position and then the element.
     */
    private val RANGE_OVER_VALUES = Regex(
        """^-?\s*range\s+(?:\$(\w+)\s*(?:,\s*\$(\w+)\s*)?:=\s*)?\$?\.Values\.([A-Za-z0-9_.-]+)\s*-?$"""
    )
    private val OTHER_OPENER = Regex("""^-?\s*(range|if|with|define|block)\b""")
    private val END = Regex("""^-?\s*end\s*-?$""")

    fun findAll(text: CharSequence): List<RangeBlock> {
        val open = ArrayDeque<Pair<MatchResult?, TextRange>>()
        val blocks = ArrayList<RangeBlock>()

        for (region in TemplateScanner.regions(text)) {
            val body = text.subSequence(region.startOffset, region.endOffset)
                .toString()
                .removeSurrounding("{{", "}}")

            when {
                END.containsMatchIn(body) -> {
                    val (opener, openerRange) = open.removeLastOrNull() ?: continue
                    if (opener != null) {
                        val (first, second, path) = opener.destructured
                        blocks += RangeBlock(
                            path = path,
                            body = TextRange(openerRange.endOffset, region.startOffset),
                            end = region,
                            // With two variables the second is the element, with one it is the first.
                            elementVariable = second.ifEmpty { first }.ifEmpty { null },
                            indexVariable = if (second.isEmpty()) null else first,
                        )
                    }
                }
                RANGE_OVER_VALUES.containsMatchIn(body) ->
                    open.addLast(RANGE_OVER_VALUES.find(body) to region)
                OTHER_OPENER.containsMatchIn(body) -> open.addLast(null to region)
            }
        }
        return blocks
    }
}
