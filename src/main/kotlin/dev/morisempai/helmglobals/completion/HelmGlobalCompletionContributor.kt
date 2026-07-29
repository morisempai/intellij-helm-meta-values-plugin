package dev.morisempai.helmglobals.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import dev.morisempai.helmglobals.HelmGlobalsSupport
import dev.morisempai.helmglobals.meta.MetaIndex
import dev.morisempai.helmglobals.meta.MetaValueRendering
import dev.morisempai.helmglobals.meta.MetaValuesService
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings

class HelmGlobalCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), HelmGlobalCompletionProvider())
    }
}

private class HelmGlobalCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val file = parameters.originalFile
        if (!HelmGlobalsSupport.isValuesFile(file)) return

        val index = MetaValuesService.getInstance(file.project).index()
        if (index.isEmpty) return

        // The dummy identifier is inserted at the caret, so everything before `offset` is
        // identical in the copy and in the original file.
        val text = file.text
        val offset = parameters.offset.coerceIn(0, text.length)
        if (!isInsideTemplateRegion(text, offset)) return

        val windowStart = (offset - LOOKBEHIND).coerceAtLeast(0)
        val match = VALUES_PREFIX.find(text.substring(windowStart, offset)) ?: return

        val parentPath = match.groupValues[1].trimEnd('.')
        val alreadyTyped = match.groupValues[2]

        val root = HelmGlobalsSettings.getInstance(file.project).variableRoot
        val lookups = HelmGlobalLookups.childLookups(index, parentPath, root)
        if (lookups.isEmpty()) return

        result.withPrefixMatcher(alreadyTyped).addAllElements(lookups)
        // Nothing else is meaningful inside a `{{ ... }}` expression.
        result.stopHere()
    }

    private fun isInsideTemplateRegion(text: CharSequence, offset: Int): Boolean {
        val prefix = text.subSequence(0, offset).toString()
        val open = prefix.lastIndexOf("{{")
        if (open < 0) return false
        return open > prefix.lastIndexOf("}}")
    }

    private companion object {
        /** How far back to look for `.Values.`; comfortably longer than any realistic path. */
        const val LOOKBEHIND = 512

        /** Matches a possibly half-typed `.Values.a.b.c` ending exactly at the caret. */
        val VALUES_PREFIX = Regex("""\$?\.Values\.((?:[A-Za-z0-9_-]+\.)*)([A-Za-z0-9_-]*)$""")
    }
}

object HelmGlobalLookups {

    /**
     * Lookup elements for the immediate children of [parentPath], restricted to the branch of the
     * tree that leads to (or lives under) [root].
     */
    fun childLookups(index: MetaIndex, parentPath: String, root: String?): List<LookupElement> {
        val multipleSources = index.sourceNames.size > 1
        return index.childrenOf(parentPath)
            .map { childName -> if (parentPath.isEmpty()) childName to childName else childName to "$parentPath.$childName" }
            .filter { (_, fullPath) -> isOnRootBranch(fullPath, root) }
            .map { (childName, fullPath) -> lookupFor(index, childName, fullPath, multipleSources) }
    }

    /**
     * `true` when [path] is [root], sits below it, or is one of the prefixes leading to it.
     * A `null` or empty [root] accepts the whole tree.
     */
    private fun isOnRootBranch(path: String, root: String?): Boolean =
        root.isNullOrEmpty() || path == root || path.startsWith("$root.") || root.startsWith("$path.")

    private fun lookupFor(
        index: MetaIndex,
        childName: String,
        fullPath: String,
        multipleSources: Boolean,
    ): LookupElement {
        val definitions = index.definitionsOf(fullPath)
        val isMapping = index.isMapping(fullPath)

        var element = LookupElementBuilder.create(childName)
            .withIcon(if (isMapping) AllIcons.Json.Object else AllIcons.Nodes.Field)

        if (isMapping) {
            val childCount = index.childrenOf(fullPath).size
            element = element
                .withTailText("  {$childCount}", true)
                .withInsertHandler(DescendInsertHandler)
        } else {
            MetaValueRendering.inlineSummary(definitions, multipleSources)?.let {
                element = element.withTailText("  = $it", true)
            }
        }

        val sources = definitions.map { it.sourceName }.distinct()
        if (sources.isNotEmpty()) {
            element = element.withTypeText(sources.joinToString(", "), true)
        }

        // Concrete values first; intermediate mappings after them.
        return PrioritizedLookupElement.withPriority(element, if (isMapping) 50.0 else 100.0)
    }
}

/** After completing an intermediate mapping, type the dot and re-open completion for its children. */
private object DescendInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val offset = context.tailOffset
        if (context.document.textLength <= offset || context.document.charsSequence[offset] != '.') {
            context.document.insertString(offset, ".")
        }
        context.editor.caretModel.moveToOffset(offset + 1)
        context.commitDocument()
        AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
    }
}
