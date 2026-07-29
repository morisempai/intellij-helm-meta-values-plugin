package dev.morisempai.helmglobals.hints

import com.intellij.codeInsight.hints.declarative.AboveLineIndentedPosition
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.psi.PsiFile
import dev.morisempai.helmglobals.meta.MetaIndex
import dev.morisempai.helmglobals.meta.MetaSequence
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
            val sequence = index.sequenceOf(block.path) ?: continue
            // Either shape works: a list of scalars used through the dot, or a list of mappings
            // whose fields the body reaches into. A mixture is not worth guessing at.
            if (sequence.items.isEmpty()) continue
            if (!sequence.allScalars && !sequence.allMappings) continue

            val body = text.substring(block.body.startOffset, block.body.endOffset)
            if (body.isBlank()) continue

            val rendered = render(body, sequence, block) ?: continue
            if (rendered.isEmpty()) continue

            rendered.forEachIndexed { line, content ->
                sink.addPresentation(
                    // A higher vertical priority sits higher up, so the index is negated for the
                    // preview to read top to bottom in iteration order.
                    position = AboveLineIndentedPosition(block.end.startOffset, -line, 0),
                    payloads = null,
                    tooltip = "${block.path}: ${sequence.items.size} items",
                    hintFormat = HintFormat.default,
                ) {
                    text(content)
                }
            }
        }
    }

    /** `null` as soon as one expression cannot be rendered, so the preview is all or nothing. */
    private fun render(body: String, sequence: MetaSequence, block: RangeBlock): List<String>? {
        val out = ArrayList<String>()
        val items = sequence.items

        for ((position, item) in items.withIndex()) {
            val fields = sequence.fields.getOrElse(position) { emptyMap() }
            val variables = buildMap {
                block.indexVariable?.let { put(it, position.toString()) }
                block.elementVariable?.let { name ->
                    if (sequence.allScalars) put(name, item)
                    // `range $service := .Values.services` then `{{ $service.port }}`.
                    fields.forEach { (field, value) -> put("$name.$field", value) }
                }
            }
            val element = Element(
                // For a mapping element there is no sensible text for a bare `{{ . }}`, so it stays
                // undecidable rather than rendering the mapping's own source text.
                dot = item.takeIf { sequence.allScalars },
                fields = fields,
                variables = variables,
            )
            val lines = renderBody(body, element) ?: return null

            for (line in lines) {
                out += line
                if (out.size >= MAX_LINES) {
                    // Rendering the remaining items only to count their lines is not worth it; say
                    // how many items are left instead of how many lines.
                    val remaining = items.size - position - 1
                    if (remaining > 0) out += "… $remaining more items"
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
    private fun renderBody(body: String, element: Element): List<String>? {
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
                    else condition(expression, element) ?: return null
                    branches.addLast(Branch(emitting = taken, taken = taken))
                }
                "else" -> {
                    val branch = branches.lastOrNull() ?: return null
                    val taken = if (ELSE_IF.containsMatchIn(expression)) {
                        condition(expression, element) ?: return null
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
                    out.append(evaluate(expression, element) ?: return null)
                }
            }
        }
        if (emitting()) out.append(body, cursor, body.length)
        if (branches.isNotEmpty()) return null

        return out.toString().lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    }

    private class Branch(var emitting: Boolean, var taken: Boolean)

    /** What one turn of the loop binds: the dot, the element's fields, and any `$` variables. */
    private class Element(
        val dot: String?,
        val fields: Map<String, String>,
        val variables: Map<String, String>,
    )

    private fun evaluate(expression: String, element: Element): String? = TemplateEvaluator.evaluate(
        expression, element.dot, element.fields, element.variables, ::resolve,
    )

    private fun condition(expression: String, element: Element): Boolean? = TemplateEvaluator.condition(
        expression, element.dot, element.fields, element.variables, ::resolve,
    )

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
