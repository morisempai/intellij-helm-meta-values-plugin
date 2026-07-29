package dev.morisempai.helmglobals.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import dev.morisempai.helmglobals.HelmGlobalsBundle
import dev.morisempai.helmglobals.HelmGlobalsSupport
import dev.morisempai.helmglobals.meta.MetaIndex
import dev.morisempai.helmglobals.meta.MetaValuesService
import dev.morisempai.helmglobals.psi.TemplateScanner
import dev.morisempai.helmglobals.psi.ValuesReference
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings

/**
 * Reports `{{ .Values.* }}` expressions whose path is absent from the meta values file, plus two
 * softer signals: a path that points at a mapping where a scalar is expected, and a path that only
 * some of several configured meta files define.
 *
 * Restricted to one branch of the tree when a variable root is configured; by default every
 * `.Values.*` path is checked.
 *
 * Stays silent when no meta values file resolves, so the inspection never fires in projects that
 * have not opted in.
 */
class UnknownGlobalVariableInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        if (!HelmGlobalsSupport.isValuesFile(file)) return PsiElementVisitor.EMPTY_VISITOR

        val project = file.project
        val service = MetaValuesService.getInstance(project)
        val index = service.index()
        if (index.isEmpty) return PsiElementVisitor.EMPTY_VISITOR

        val root = HelmGlobalsSettings.getInstance(project).variableRoot
        val metaFiles = service.metaVirtualFiles()

        return object : PsiElementVisitor() {
            /**
             * Validation runs over the file's raw text rather than over its scalars. The YAML parser
             * cannot represent every Helm construct — a bare `{{- if ... }}` line, or a template
             * used as a key, never becomes a YAML scalar — but the braces are always there in the
             * text, so scanning it catches every expression in the file.
             */
            override fun visitFile(psiFile: PsiFile) {
                val text = psiFile.text
                for (reference in TemplateScanner.scan(text)) {
                    if (!reference.isUnder(root)) continue
                    if (reference.pathRange.endOffset > text.length) continue
                    // Commented-out examples are not errors.
                    if (isInsideComment(psiFile, reference.pathRange.startOffset)) continue
                    inspect(holder, isOnTheFly, psiFile, index, reference, metaFiles)
                }
            }
        }
    }

    private fun isInsideComment(file: PsiFile, offset: Int): Boolean =
        PsiTreeUtil.getParentOfType(file.findElementAt(offset), PsiComment::class.java, false) != null

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

        val missing = index.sourcesMissing(reference.path)
        if (missing.isNotEmpty()) {
            register(
                holder, isOnTheFly, host, reference.pathRange,
                HelmGlobalsBundle.message(
                    "inspection.missing.in.some.sources",
                    reference.path,
                    missing.joinToString(", "),
                ),
                ProblemHighlightType.WEAK_WARNING,
                *fixesFor(reference.path, metaFiles.filter { it.name in missing }),
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
