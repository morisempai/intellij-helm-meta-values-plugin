package dev.morisempai.helmglobals.hints

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.morisempai.helmglobals.HelmGlobalsSupport
import dev.morisempai.helmglobals.meta.MetaIndex
import dev.morisempai.helmglobals.meta.MetaValueRendering
import dev.morisempai.helmglobals.psi.HelmTemplates
import dev.morisempai.helmglobals.psi.ValuesReference
import dev.morisempai.helmglobals.template.PipeEvaluator
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Renders the value a template expression resolves to right after the closing `}}`, e.g.
 * `registry: {{ .Values.global.registry }}` ⟶ `= registry.dev.corp`.
 */
class HelmGlobalsInlayProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        val context = HelmGlobalsSupport.contextFor(file) ?: return null
        if (!HelmGlobalsSettings.getInstance(file.project).showInlayValues) return null
        return ResolvedValueCollector(context.index, context.root)
    }
}

private class ResolvedValueCollector(
    private val index: MetaIndex,
    private val root: String?,
) : SharedBypassCollector {

    private val multipleSources = index.sourceNames.size > 1

    override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
        if (element !is YAMLScalar) return
        val references = HelmTemplates.referencesIn(element)
        if (references.isEmpty()) return

        // One hint per `{{ ... }}` region, even when it mentions several variables.
        for ((_, group) in references.groupBy { it.templateRange }) {
            val anchor = HelmTemplates.templateEndOffset(element, group.first())

            val parts = group
                .filter { it.isUnder(root) }
                .mapNotNull { reference ->
                    val summary = summaryOf(reference) ?: return@mapNotNull null
                    if (group.size == 1) summary else "${reference.path.substringAfterLast('.')} = $summary"
                }
                .distinct()

            if (parts.isEmpty()) continue

            val tooltip = group
                .filter { it.isUnder(root) }
                .joinToString("\n") { reference -> tooltipFor(reference.path) }

            sink.addPresentation(
                position = InlineInlayPosition(anchor, relatedToPrevious = true),
                payloads = null,
                tooltip = tooltip,
                hintFormat = HintFormat.default,
            ) {
                text(if (group.size == 1) "= ${parts.first()}" else parts.joinToString(", "))
            }
        }
    }

    /**
     * The value as the expression actually renders it: a pipe chain of plain string functions is
     * applied, so `| quote` shows the quotes. Anything the evaluator does not model falls back to
     * the raw value.
     */
    private fun summaryOf(reference: ValuesReference): String? {
        val definitions = index.definitionsOf(reference.path)
        MetaValueRendering.singleScalarValue(definitions)?.let { raw ->
            PipeEvaluator.apply(reference.templateBody, reference.path, raw)?.let { piped ->
                return MetaValueRendering.abbreviate(MetaValueRendering.quoteIfBlank(piped))
            }
        }
        return MetaValueRendering.inlineSummary(definitions, multipleSources)
    }

    private fun tooltipFor(path: String): String {
        val definitions = index.definitionsOf(path)
        if (definitions.isEmpty()) return path
        return definitions.joinToString("\n") { definition ->
            val value = definition.presentableValue?.let { MetaValueRendering.quoteIfBlank(it) } ?: "{…}"
            "$path = $value  (${definition.sourceName})"
        }
    }
}
