package dev.morisempai.helmglobals.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import dev.morisempai.helmglobals.HelmGlobalsBundle
import dev.morisempai.helmglobals.HelmGlobalsSupport
import dev.morisempai.helmglobals.meta.MetaValuesService
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings

/**
 * Reports a `# helm-globals:` directive naming a meta values file that does not exist.
 *
 * Without this the mistake is invisible: an unresolvable path leaves the file with no variables, and
 * every other feature simply goes quiet.
 */
class UnresolvedMetaFileInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        if (!HelmGlobalsSettings.getInstance(file.project).isEnabled) return PsiElementVisitor.EMPTY_VISITOR
        val directive = HelmGlobalsSupport.directiveOf(file) ?: return PsiElementVisitor.EMPTY_VISITOR
        if (directive.metaPaths.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR

        val service = MetaValuesService.getInstance(file.project)
        val virtualFile = file.originalFile.virtualFile

        return object : PsiElementVisitor() {
            override fun visitFile(psiFile: PsiFile) {
                for (declared in directive.metaPaths) {
                    if (service.resolvePath(declared.text, virtualFile) != null) continue
                    if (declared.range.endOffset > psiFile.textLength) continue
                    holder.registerProblem(
                        holder.manager.createProblemDescriptor(
                            psiFile,
                            declared.range,
                            HelmGlobalsBundle.message("inspection.meta.file.not.found", declared.text),
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            isOnTheFly,
                        )
                    )
                }
            }
        }
    }
}
