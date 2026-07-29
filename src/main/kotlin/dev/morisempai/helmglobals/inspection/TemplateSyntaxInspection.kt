package dev.morisempai.helmglobals.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import dev.morisempai.helmglobals.HelmGlobalsBundle
import dev.morisempai.helmglobals.HelmGlobalsSupport
import dev.morisempai.helmglobals.template.TemplateProblem
import dev.morisempai.helmglobals.template.TemplateProblemKind
import dev.morisempai.helmglobals.template.TemplateSyntax

/**
 * Reports template expressions that cannot be parsed at all: an unclosed `{{`, a stray `}}`,
 * unbalanced parentheses or quotes, and block actions that do not pair up with their `end`.
 *
 * Nothing is said about what an expression *means*. Only breakage no Helm template could recover
 * from is reported here — see [TemplateSyntax].
 */
class TemplateSyntaxInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (HelmGlobalsSupport.contextFor(holder.file) == null) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitFile(psiFile: PsiFile) {
                for (problem in TemplateSyntax.problems(psiFile.text)) {
                    if (problem.range.endOffset > psiFile.textLength) continue
                    holder.registerProblem(
                        holder.manager.createProblemDescriptor(
                            psiFile,
                            problem.range,
                            message(problem),
                            ProblemHighlightType.GENERIC_ERROR,
                            isOnTheFly,
                        )
                    )
                }
            }
        }
    }

    private fun message(problem: TemplateProblem): String = when (problem.kind) {
        TemplateProblemKind.UNCLOSED -> HelmGlobalsBundle.message("inspection.template.unclosed")
        TemplateProblemKind.STRAY_CLOSE -> HelmGlobalsBundle.message("inspection.template.stray.close")
        TemplateProblemKind.EMPTY -> HelmGlobalsBundle.message("inspection.template.empty")
        TemplateProblemKind.UNBALANCED_PARENTHESES ->
            HelmGlobalsBundle.message("inspection.template.unbalanced.parentheses")
        TemplateProblemKind.UNTERMINATED_STRING ->
            HelmGlobalsBundle.message("inspection.template.unterminated.string")
        TemplateProblemKind.UNEXPECTED_END -> HelmGlobalsBundle.message("inspection.template.unexpected.end")
        TemplateProblemKind.UNEXPECTED_ELSE -> HelmGlobalsBundle.message("inspection.template.unexpected.else")
        TemplateProblemKind.MISSING_END ->
            HelmGlobalsBundle.message("inspection.template.missing.end", problem.keyword)
        TemplateProblemKind.EMPTY_PIPELINE_STAGE ->
            HelmGlobalsBundle.message("inspection.template.empty.pipeline.stage")
        TemplateProblemKind.MISSING_ASSIGNED_VALUE ->
            HelmGlobalsBundle.message("inspection.template.missing.assigned.value")
        TemplateProblemKind.MISSING_ARGUMENT ->
            HelmGlobalsBundle.message("inspection.template.missing.argument", problem.keyword)
        TemplateProblemKind.UNEXPECTED_ARGUMENT ->
            HelmGlobalsBundle.message("inspection.template.unexpected.argument", problem.keyword)
    }
}
