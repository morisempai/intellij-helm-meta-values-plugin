package dev.morisempai.helmglobals.inspection

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.PsiNavigateUtil
import dev.morisempai.helmglobals.HelmGlobalsBundle
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Creates the missing key (and any missing parents) in a meta values file, with an empty value,
 * then navigates there so the value can be filled in.
 */
class AddGlobalVariableFix(
    private val path: String,
    private val metaFileUrl: String,
    private val metaFileName: String,
) : LocalQuickFix {

    override fun getName(): String = HelmGlobalsBundle.message("fix.add.variable", path, metaFileName)

    override fun getFamilyName(): String = HelmGlobalsBundle.message("fix.add.variable.family")

    // The write action is opened explicitly, after the target file has been prepared for write.
    override fun startInWriteAction(): Boolean = false

    override fun availableInBatchMode(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val virtualFile = VirtualFileManager.getInstance().findFileByUrl(metaFileUrl) ?: return
        val metaFile = PsiManager.getInstance(project).findFile(virtualFile) as? YAMLFile ?: return
        if (!FileModificationService.getInstance().preparePsiElementForWrite(metaFile)) return

        WriteCommandAction.runWriteCommandAction(
            project,
            name,
            null,
            Runnable { addPath(project, metaFile) },
            metaFile,
        )

        findKeyValue(metaFile, path)?.let { PsiNavigateUtil.navigate(it) }
    }

    private fun addPath(project: Project, metaFile: YAMLFile) {
        val segments = path.split('.').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return

        val topLevel = metaFile.documents.firstOrNull()?.topLevelValue
        if (topLevel !is YAMLMapping) {
            // Empty file, or a document whose root is not a mapping: append the branch as text.
            val document = PsiDocumentManager.getInstance(project).getDocument(metaFile) ?: return
            appendAsText(project, document, segments)
            return
        }

        var mapping: YAMLMapping = topLevel
        var consumed = 0
        while (consumed < segments.size) {
            val existing = mapping.getKeyValueByKey(segments[consumed]) ?: break
            val value = existing.value
            // An existing scalar on the way down means the path conflicts; leave the file alone.
            if (value !is YAMLMapping) return
            mapping = value
            consumed++
        }
        if (consumed == segments.size) return // already defined

        val generator = YAMLElementGenerator.getInstance(project)
        val dummy = generator.createDummyYamlWithText(renderBranch(segments.drop(consumed)))
        val newKeyValue = PsiTreeUtil.findChildOfType(dummy, YAMLKeyValue::class.java) ?: return
        mapping.putKeyValue(newKeyValue)
    }

    private fun appendAsText(project: Project, document: Document, segments: List<String>) {
        val separator = if (document.textLength > 0 && !document.text.endsWith("\n")) "\n" else ""
        document.insertString(document.textLength, separator + renderBranch(segments))
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    /** `["global", "image", "registry"]` becomes `global:\n  image:\n    registry: ""\n`. */
    private fun renderBranch(segments: List<String>): String = buildString {
        segments.forEachIndexed { depth, segment ->
            append("  ".repeat(depth)).append(segment).append(':')
            if (depth == segments.lastIndex) append(" \"\"")
            append('\n')
        }
    }

    private fun findKeyValue(metaFile: YAMLFile, dottedPath: String): YAMLKeyValue? {
        var mapping = metaFile.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return null
        var result: YAMLKeyValue? = null
        for (segment in dottedPath.split('.')) {
            result = mapping.getKeyValueByKey(segment) ?: return null
            mapping = result.value as? YAMLMapping ?: return result
        }
        return result
    }
}
