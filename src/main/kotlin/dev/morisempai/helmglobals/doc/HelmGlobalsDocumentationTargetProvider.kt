package dev.morisempai.helmglobals.doc

import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import dev.morisempai.helmglobals.HelmGlobalsSupport
import dev.morisempai.helmglobals.meta.MetaValueRendering
import dev.morisempai.helmglobals.meta.MetaValuesService
import dev.morisempai.helmglobals.psi.HelmTemplates
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Ctrl+hover / Ctrl+Q over a `{{ .Values.global.* }}` path shows the value it resolves to in every
 * configured meta values file, rather than just the key the reference happens to resolve to first.
 */
class HelmGlobalsDocumentationTargetProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (!HelmGlobalsSupport.isValuesFile(file)) return emptyList()

        val leaf = file.findElementAt(offset) ?: return emptyList()
        val scalar = PsiTreeUtil.getParentOfType(leaf, YAMLScalar::class.java, false) ?: return emptyList()

        val project = file.project
        val root = HelmGlobalsSettings.getInstance(project).variableRoot
        val localOffset = offset - scalar.textRange.startOffset

        val reference = HelmTemplates.referencesIn(scalar)
            .firstOrNull { it.isUnder(root) && it.pathRange.containsOffset(localOffset) }
            ?: return emptyList()

        val segmentIndex = reference.segmentRanges.indexOfFirst { it.containsOffset(localOffset) }
        val path = if (segmentIndex >= 0) reference.pathUpTo(segmentIndex) else reference.path

        if (!MetaValuesService.getInstance(project).index().contains(path)) return emptyList()
        return listOf(HelmGlobalDocumentationTarget(project, path))
    }
}

private class HelmGlobalDocumentationTarget(
    private val project: Project,
    private val path: String,
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val capturedProject = project
        val capturedPath = path
        return Pointer {
            if (capturedProject.isDisposed) null else HelmGlobalDocumentationTarget(capturedProject, capturedPath)
        }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(path)
            .icon(AllIcons.Nodes.Field)
            .presentation()

    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(buildHtml())

    private fun buildHtml(): String {
        val index = MetaValuesService.getInstance(project).index()
        val definitions = index.definitionsOf(path)

        return buildString {
            append(DocumentationMarkup.DEFINITION_START)
            append("{{ .Values.").append(StringUtil.escapeXmlEntities(path)).append(" }}")
            append(DocumentationMarkup.DEFINITION_END)

            append(DocumentationMarkup.SECTIONS_START)
            for (definition in definitions) {
                val rendered = definition.presentableValue
                    ?.let { StringUtil.escapeXmlEntities(MetaValueRendering.quoteIfBlank(it)) }
                    ?: "<i>object with ${index.childrenOf(path).size} key(s)</i>"
                append(DocumentationMarkup.SECTION_HEADER_START)
                append(StringUtil.escapeXmlEntities(definition.sourceName))
                append(DocumentationMarkup.SECTION_SEPARATOR)
                append("<p>").append(rendered)
                append(DocumentationMarkup.SECTION_END)
            }

            val missing = index.sourcesMissing(path)
            if (missing.isNotEmpty()) {
                append(DocumentationMarkup.SECTION_HEADER_START)
                append("Missing in")
                append(DocumentationMarkup.SECTION_SEPARATOR)
                append("<p>").append(StringUtil.escapeXmlEntities(missing.joinToString(", ")))
                append(DocumentationMarkup.SECTION_END)
            }
            append(DocumentationMarkup.SECTIONS_END)
        }
    }
}
