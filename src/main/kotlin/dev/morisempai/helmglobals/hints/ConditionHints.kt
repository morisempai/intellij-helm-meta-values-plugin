package dev.morisempai.helmglobals.hints

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.morisempai.helmglobals.meta.MetaIndex
import dev.morisempai.helmglobals.meta.MetaValueRendering
import dev.morisempai.helmglobals.psi.TemplateScanner
import dev.morisempai.helmglobals.psi.ValuesReference
import dev.morisempai.helmglobals.template.TemplateEvaluator

/**
 * Shows whether an `if` branch is taken, given what the meta file says:
 * `{{- if .Values.global.ingress.enabled }}` ⟶ `= true`.
 *
 * Read from the file text rather than the PSI because a bare `{{- if ... }}` line sits at mapping
 * level, where the YAML parser leaves nothing to attach a hint to — and because the parser splits
 * the line differently depending on the condition, so the shape of the scalar cannot be relied on.
 */
class ConditionHints(private val index: MetaIndex, private val root: String?) {

    fun collect(file: PsiFile, sink: InlayTreeSink) {
        val text = file.text

        for (region in conditionRegions(text)) {
            val expression = text.substring(region.startOffset, region.endOffset).removeSurrounding("{{", "}}")
            val taken = TemplateEvaluator.condition(expression) { path ->
                if (!ValuesReference.isUnder(path, root)) null
                else MetaValueRendering.singleScalarValue(index.definitionsOf(path))
            } ?: continue

            sink.addPresentation(
                position = InlineInlayPosition(region.endOffset, relatedToPrevious = true),
                payloads = null,
                tooltip = null,
                hintFormat = HintFormat.default,
            ) {
                text("= $taken")
            }
        }
    }

    companion object {
        private val CONDITION = Regex("""^-?\s*(?:else\s+)?if\b""")

        /** The `{{ if … }}` and `{{ else if … }}` expressions in [text]. */
        fun conditionRegions(text: CharSequence): List<TextRange> = TemplateScanner.regions(text).filter {
            CONDITION.containsMatchIn(
                text.subSequence(it.startOffset, it.endOffset).toString().removeSurrounding("{{", "}}")
            )
        }
    }
}
