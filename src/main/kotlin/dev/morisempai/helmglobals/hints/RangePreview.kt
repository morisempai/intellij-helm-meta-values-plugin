package dev.morisempai.helmglobals.hints

import com.intellij.codeInsight.hints.declarative.AboveLineIndentedPosition
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.psi.PsiFile
import dev.morisempai.helmglobals.meta.MetaIndex
import dev.morisempai.helmglobals.meta.MetaValueRendering
import dev.morisempai.helmglobals.psi.TemplateScanner
import dev.morisempai.helmglobals.psi.ValuesReference
import dev.morisempai.helmglobals.template.RangeBlock
import dev.morisempai.helmglobals.template.RangeBlocks
import dev.morisempai.helmglobals.template.TemplateEvaluator

/**
 * Shows what a `range` over a list from the meta file produces, one line per rendered line, stacked
 * just above the closing `{{ end }}`:
 *
 * ```yaml
 * hosts:
 * {{- range .Values.global.hosts }}
 *   - {{ . }}
 *       - a.dev.corp        ← preview
 *       - b.dev.corp
 * {{- end }}
 * ```
 *
 * The preview appears only when every line of the body can be rendered exactly: the list has to
 * hold scalars, and each expression in the body has to be one [TemplateEvaluator] understands. A
 * partially rendered loop would be a lie about what Helm will produce.
 */
class RangePreview(private val index: MetaIndex, private val root: String?) {

    fun collect(file: PsiFile, sink: InlayTreeSink) {
        val text = file.text

        for (block in RangeBlocks.findAll(text)) {
            if (!ValuesReference.isUnder(block.path, root)) continue
            val turns = turnsOf(block.path) ?: continue

            val body = text.substring(block.body.startOffset, block.body.endOffset)
            if (body.isBlank()) continue

            val rendered = render(body, turns, block) ?: continue
            if (rendered.isEmpty()) continue

            rendered.forEachIndexed { line, content ->
                sink.addPresentation(
                    // A higher vertical priority sits higher up, so the index is negated for the
                    // preview to read top to bottom in iteration order.
                    position = AboveLineIndentedPosition(block.end.startOffset, -line, 0),
                    payloads = null,
                    tooltip = "${block.path}: ${turns.size} entries",
                    hintFormat = HintFormat.default,
                ) {
                    text(content)
                }
            }
        }
    }

    /**
     * One turn of the loop: what the body sees for a single element. [key] is the position for a
     * list and the key for a mapping, which is what Go binds the first `range` variable to.
     */
    private class Turn(val key: String, val dot: String?, val fields: Map<String, String>)

    /**
     * The turns a `range` over [path] would make, or `null` when the value is not something to
     * iterate. A list of scalars is seen through the dot, a list of mappings or a mapping through
     * its fields.
     */
    private fun turnsOf(path: String): List<Turn>? {
        index.sequenceOf(path)?.let { sequence ->
            // A mixture of scalars and mappings is not worth guessing at.
            if (sequence.items.isEmpty()) return null
            if (!sequence.allScalars && !sequence.allMappings) return null
            return sequence.items.mapIndexed { position, item ->
                Turn(
                    key = position.toString(),
                    // A mapping element has no sensible text for a bare `{{ . }}`, so it stays
                    // undecidable rather than rendering the mapping's own source text.
                    dot = item.takeIf { sequence.allScalars },
                    fields = sequence.fields.getOrElse(position) { emptyMap() },
                )
            }
        }
        return index.entriesOf(path)
            ?.takeIf { it.isNotEmpty() }
            ?.map { Turn(key = it.key, dot = it.scalar, fields = it.fields) }
    }

    /** `null` as soon as one expression cannot be rendered, so the preview is all or nothing. */
    private fun render(body: String, turns: List<Turn>, block: RangeBlock): List<String>? {
        val out = ArrayList<String>()

        for ((position, turn) in turns.withIndex()) {
            val bindings = TemplateEvaluator.Bindings(
                dot = turn.dot,
                fields = turn.fields,
                // The element comes from the meta file, so its fields are known exhaustively and a
                // condition on one that is absent can be answered with "no" rather than "unknown".
                fieldsAreComplete = true,
                variables = block.keyVariable?.let { mapOf(it to turn.key) }.orEmpty(),
                elementVariable = block.elementVariable,
            )
            val lines = renderBody(body, bindings) ?: return null

            for (line in lines) {
                out += line
                if (out.size >= MAX_LINES) {
                    // Rendering the rest only to count their lines is not worth it; say how many
                    // entries are left instead of how many lines.
                    val remaining = turns.size - position - 1
                    if (remaining > 0) out += "… $remaining more entries"
                    return out
                }
            }
        }
        return out
    }

    /**
     * Renders the loop body for one element, following `if` / `else if` / `else` so that only the
     * branches actually taken appear. Blank lines are dropped, since the interesting output is the
     * lines that carry content.
     */
    private fun renderBody(body: String, bindings: TemplateEvaluator.Bindings): List<String>? {
        val out = StringBuilder()
        var cursor = 0
        val branches = ArrayDeque<Branch>()
        fun emitting() = branches.all { it.emitting }

        for (region in TemplateScanner.regions(body)) {
            if (emitting()) out.append(body, cursor, region.startOffset)
            cursor = region.endOffset

            val expression = body.substring(region.startOffset, region.endOffset).removeSurrounding("{{", "}}")
            val keyword = KEYWORD.find(expression)?.groupValues?.get(1)

            when (keyword) {
                "if" -> {
                    // A nested condition inside a branch that is not rendering does not need to be
                    // decided, and often cannot be.
                    val taken = if (!emitting()) false
                    else condition(expression, bindings) ?: return null
                    branches.addLast(Branch(emitting = taken, taken = taken))
                }
                "else" -> {
                    val branch = branches.lastOrNull() ?: return null
                    val taken = if (ELSE_IF.containsMatchIn(expression)) {
                        condition(expression, bindings) ?: return null
                    } else {
                        true
                    }
                    branch.emitting = !branch.taken && taken
                    branch.taken = branch.taken || taken
                }
                "end" -> branches.removeLastOrNull() ?: return null
                // A nested loop, or a `with` rebinding the dot, is more than this preview models.
                "range", "with", "define", "block" -> return null
                else -> if (emitting()) {
                    out.append(evaluate(expression, bindings) ?: return null)
                }
            }
        }
        if (emitting()) out.append(body, cursor, body.length)
        if (branches.isNotEmpty()) return null

        return out.toString().lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    }

    private class Branch(var emitting: Boolean, var taken: Boolean)

    private fun evaluate(expression: String, bindings: TemplateEvaluator.Bindings): String? =
        TemplateEvaluator.evaluate(expression, bindings, ::resolve)

    private fun condition(expression: String, bindings: TemplateEvaluator.Bindings): Boolean? =
        TemplateEvaluator.condition(expression, bindings, ::resolve)

    private fun resolve(path: String): String? =
        if (!ValuesReference.isUnder(path, root)) null
        else MetaValueRendering.singleScalarValue(index.definitionsOf(path))

    private companion object {
        /** A long list would bury the file it is meant to explain. */
        const val MAX_LINES = 12

        /** The action a control expression opens or closes, ignoring trim markers. */
        val KEYWORD = Regex("""^-?\s*(if|else|end|range|with|define|block)\b""")
        val ELSE_IF = Regex("""^-?\s*else\s+if\b""")
    }
}
