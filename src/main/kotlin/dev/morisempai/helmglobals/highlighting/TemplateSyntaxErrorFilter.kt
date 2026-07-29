package dev.morisempai.helmglobals.highlighting

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile
import dev.morisempai.helmglobals.HelmGlobalsSupport
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Drops the YAML errors that a Helm expression provokes.
 *
 * A values file carrying control flow is not valid YAML — `{{- range .Values.services }}` on its own
 * line is reported as *Invalid child element in a block mapping* by the YAML annotator, and a
 * template used as a key or a value spanning lines produces similar parse errors. The file is
 * correct as far as Helm is concerned, so the errors are noise from a template the YAML support
 * knows nothing about.
 *
 * Only errors overlapping a `{{ … }}` region are dropped, and only in files this plugin already
 * recognises as templated values files, so genuine YAML mistakes elsewhere still show.
 */
class TemplateSyntaxErrorFilter : HighlightInfoFilter {

    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        if (file !is YAMLFile) return true
        if (highlightInfo.severity != HighlightSeverity.ERROR) return true
        if (!HelmGlobalsSettings.getInstance(file.project).hideTemplateSyntaxErrors) return true
        if (HelmGlobalsSupport.contextFor(file) == null) return true

        return HelmGlobalsSupport.templateRegions(file).none { region ->
            region.startOffset < highlightInfo.endOffset && highlightInfo.startOffset < region.endOffset
        }
    }
}
