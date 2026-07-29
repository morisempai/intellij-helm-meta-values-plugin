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
            val sequence = index.sequenceOf(block.path) ?: continue
            if (!sequence.allScalars || sequence.items.isEmpty()) continue

            val bodyLines = text.substring(block.body.startOffset, block.body.endOffset)
                .lines()
                .filter { it.isNotBlank() }
            if (bodyLines.isEmpty()) continue

            val rendered = render(bodyLines, sequence.items, block) ?: continue

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
    private fun render(bodyLines: List<String>, items: List<String>, block: RangeBlock): List<String>? {
        val out = ArrayList<String>()

        for ((position, item) in items.withIndex()) {
            val variables = buildMap {
                block.elementVariable?.let { put(it, item) }
                block.indexVariable?.let { put(it, position.toString()) }
            }
            for (line in bodyLines) {
                out += renderLine(line, item, variables) ?: return null
                if (out.size >= MAX_LINES) {
                    val remaining = items.size * bodyLines.size - out.size
                    if (remaining > 0) out += "… $remaining more"
                    return out
                }
            }
        }
        return out
    }

    private fun renderLine(line: String, item: String, variables: Map<String, String>): String? {
        val out = StringBuilder()
        var cursor = 0

        for (region in TemplateScanner.regions(line)) {
            val body = line.substring(region.startOffset, region.endOffset).removeSurrounding("{{", "}}")
            val value = TemplateEvaluator.evaluate(body, dot = item, variables = variables) { path ->
                if (!ValuesReference.isUnder(path, root)) null
                else MetaValueRendering.singleScalarValue(index.definitionsOf(path))
            } ?: return null

            out.append(line, cursor, region.startOffset).append(value)
            cursor = region.endOffset
        }

        // A body line without any expression repeats verbatim, which is still worth showing.
        out.append(line, cursor, line.length)
        return out.toString().trimEnd()
    }

    private companion object {
        /** A long list would bury the file it is meant to explain. */
        const val MAX_LINES = 12
    }
}
