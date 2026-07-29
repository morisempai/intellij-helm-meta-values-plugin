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
import dev.morisempai.helmglobals.template.TemplateEvaluator
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

            // Preferably the value the whole expression renders; only when it cannot be worked out
            // does the hint fall back to listing the variables it mentions.
            val whole = evaluateWhole(group.first().templateBody)
            val parts = if (whole != null) listOf(whole) else {
                group
                    .filter { it.isUnder(root) }
                    .mapNotNull { reference ->
                        val summary = summaryOf(reference) ?: return@mapNotNull null
                        if (group.size == 1) summary else "${reference.path.substringAfterLast('.')} = $summary"
                    }
                    .distinct()
            }

            if (parts.isEmpty()) continue
            val singleValue = whole != null || group.size == 1

            val tooltip = group
                .filter { it.isUnder(root) }
                .joinToString("\n") { reference -> tooltipFor(reference.path) }

            sink.addPresentation(
                position = InlineInlayPosition(anchor, relatedToPrevious = true),
                payloads = null,
                tooltip = tooltip,
                hintFormat = HintFormat.default,
            ) {
                text(if (singleValue) "= ${parts.first()}" else parts.joinToString(", "))
            }
        }
    }

    /**
     * The string the whole expression renders, or `null` when it uses anything the evaluator does
     * not model. Only paths in scope resolve, so a configured variable root still means the plugin
     * claims no knowledge of what lies outside it.
     */
    private fun evaluateWhole(templateBody: String): String? {
        val value = TemplateEvaluator.evaluate(templateBody) { path ->
            if (!ValuesReference.isUnder(path, root)) null
            else MetaValueRendering.singleScalarValue(index.definitionsOf(path))
        } ?: return null
        return MetaValueRendering.abbreviate(MetaValueRendering.quoteIfBlank(value))
    }

    private fun summaryOf(reference: ValuesReference): String? =
        MetaValueRendering.inlineSummary(index.definitionsOf(reference.path), multipleSources)

    private fun tooltipFor(path: String): String {
        val definitions = index.definitionsOf(path)
        if (definitions.isEmpty()) return path
        return definitions.joinToString("\n") { definition ->
            val value = definition.presentableValue?.let { MetaValueRendering.quoteIfBlank(it) } ?: "{…}"
            "$path = $value  (${definition.sourceName})"
        }
    }
}
