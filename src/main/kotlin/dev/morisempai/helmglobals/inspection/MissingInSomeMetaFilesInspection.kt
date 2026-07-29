package dev.morisempai.helmglobals.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import dev.morisempai.helmglobals.HelmGlobalsBundle
import dev.morisempai.helmglobals.HelmGlobalsSupport
import dev.morisempai.helmglobals.psi.HelmTemplates

/**
 * Reports a variable that some, but not all, of the configured meta values files define.
 *
 * Only meaningful when the meta files are parallel environments — `dev.yaml` and `prod.yaml`
 * describing the same variables with different values — where a gap means someone forgot to add the
 * variable to one environment. When the files are complementary instead, each contributing its own
 * set of keys, every variable is missing from all the others and the report is pure noise. That is
 * the commoner arrangement, so this inspection is off by default.
 */
class MissingInSomeMetaFilesInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val context = HelmGlobalsSupport.contextFor(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        // With a single meta file there is nothing to compare against.
        if (context.index.sourceNames.size < 2) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitFile(psiFile: PsiFile) {
                for (reference in HelmTemplates.referencesIn(psiFile, context.root)) {
                    if (!context.index.contains(reference.path)) continue
                    val missing = context.index.sourcesMissing(reference.path)
                    if (missing.isEmpty()) continue

                    val fixes = context.metaFiles
                        .filter { it.name in missing }
                        .map<VirtualFile, LocalQuickFix> { AddGlobalVariableFix(reference.path, it.url, it.name) }

                    holder.registerProblem(
                        holder.manager.createProblemDescriptor(
                            psiFile,
                            reference.pathRange,
                            HelmGlobalsBundle.message(
                                "inspection.missing.in.some.sources",
                                reference.path,
                                missing.joinToString(", "),
                            ),
                            ProblemHighlightType.WEAK_WARNING,
                            isOnTheFly,
                            *fixes.toTypedArray(),
                        )
                    )
                }
            }
        }
    }
}
