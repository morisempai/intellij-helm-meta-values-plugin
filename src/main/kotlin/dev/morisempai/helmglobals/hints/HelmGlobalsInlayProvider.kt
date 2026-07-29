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
import dev.morisempai.helmglobals.meta.MetaValuesService
import dev.morisempai.helmglobals.psi.HelmTemplates
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Renders the value a template expression resolves to right after the closing `}}`, e.g.
 * `registry: {{ .Values.global.registry }}` ⟶ `= registry.dev.corp`.
 */
class HelmGlobalsInlayProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (!HelmGlobalsSupport.isValuesFile(file)) return null
        val settings = HelmGlobalsSettings.getInstance(file.project)
        if (!settings.showInlayValues) return null
        val index = MetaValuesService.getInstance(file.project).index()
        if (index.isEmpty) return null
        return ResolvedValueCollector(index, settings.variableRoot)
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
                    val summary = MetaValueRendering.inlineSummary(
                        index.definitionsOf(reference.path),
                        multipleSources,
                    ) ?: return@mapNotNull null
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

    private fun tooltipFor(path: String): String {
        val definitions = index.definitionsOf(path)
        if (definitions.isEmpty()) return path
        return definitions.joinToString("\n") { definition ->
            val value = definition.presentableValue?.let { MetaValueRendering.quoteIfBlank(it) } ?: "{…}"
            "$path = $value  (${definition.sourceName})"
        }
    }
}
