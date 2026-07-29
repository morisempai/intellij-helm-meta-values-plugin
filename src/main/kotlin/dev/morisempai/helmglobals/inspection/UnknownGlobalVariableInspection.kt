package dev.morisempai.helmglobals.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import dev.morisempai.helmglobals.HelmGlobalsBundle
import dev.morisempai.helmglobals.HelmGlobalsSupport
import dev.morisempai.helmglobals.meta.MetaIndex
import dev.morisempai.helmglobals.psi.HelmTemplates
import dev.morisempai.helmglobals.psi.ValuesReference

/**
 * Reports `{{ .Values.* }}` expressions whose path is absent from the meta values file, plus one
 * softer signal: a path that points at a mapping where a scalar is expected.
 *
 * A variable that only *some* of several meta files define is deliberately not reported here — see
 * [MissingInSomeMetaFilesInspection], which is a separate, opt-in inspection.
 *
 * Restricted to one branch of the tree when a variable root is configured; by default every
 * `.Values.*` path is checked.
 *
 * Stays silent when no meta values file resolves, so the inspection never fires in projects that
 * have not opted in.
 */
class UnknownGlobalVariableInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val context = HelmGlobalsSupport.contextFor(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val index = context.index
        val root = context.root
        val metaFiles = context.metaFiles

        return object : PsiElementVisitor() {
            override fun visitFile(psiFile: PsiFile) {
                for (reference in HelmTemplates.referencesIn(psiFile, root)) {
                    inspect(holder, isOnTheFly, psiFile, index, reference, metaFiles)
                }
            }
        }
    }

    private fun inspect(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
        host: PsiElement,
        index: MetaIndex,
        reference: ValuesReference,
        metaFiles: List<VirtualFile>,
    ) {
        if (!index.contains(reference.path)) {
            reportUnknown(holder, isOnTheFly, host, index, reference, metaFiles)
            return
        }

        if (index.isMapping(reference.path) && !reference.usesValueAsStructure) {
            register(
                holder, isOnTheFly, host, reference.pathRange,
                HelmGlobalsBundle.message("inspection.object.used.as.value", reference.path),
                ProblemHighlightType.WEAK_WARNING,
            )
        }
    }

    private fun reportUnknown(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
        host: PsiElement,
        index: MetaIndex,
        reference: ValuesReference,
        metaFiles: List<VirtualFile>,
    ) {
        // Underline the first segment that could not be resolved rather than the whole path.
        val knownPrefix = index.longestKnownPrefix(reference.path)
        val knownDepth = if (knownPrefix.isEmpty()) 0 else knownPrefix.split('.').size
        val range = reference.segmentRanges.getOrNull(knownDepth) ?: reference.pathRange

        val message = if (index.sourceNames.size == 1) {
            HelmGlobalsBundle.message("inspection.unknown.variable.in", reference.path, index.sourceNames.first())
        } else {
            HelmGlobalsBundle.message("inspection.unknown.variable", reference.path)
        }

        register(
            holder, isOnTheFly, host, range, message,
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            *fixesFor(reference.path, metaFiles),
        )
    }

    private fun fixesFor(path: String, targets: List<VirtualFile>): Array<LocalQuickFix> =
        targets.map<VirtualFile, LocalQuickFix> { AddGlobalVariableFix(path, it.url, it.name) }.toTypedArray()

    private fun register(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
        host: PsiElement,
        rangeInHost: TextRange,
        message: String,
        highlightType: ProblemHighlightType,
        vararg fixes: LocalQuickFix,
    ) {
        val descriptor = holder.manager.createProblemDescriptor(
            host,
            rangeInHost,
            message,
            highlightType,
            isOnTheFly,
            *fixes,
        )
        holder.registerProblem(descriptor)
    }
}
