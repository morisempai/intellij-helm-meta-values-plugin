package dev.morisempai.helmglobals.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import dev.morisempai.helmglobals.meta.MetaValuesService

/**
 * Reference from one segment of a `{{ .Values.<path> }}` expression to the matching key in the
 * meta values file(s). Poly-variant because several meta files may define the same path.
 *
 * The reference is soft: unresolved paths are reported by
 * [dev.morisempai.helmglobals.inspection.UnknownGlobalVariableInspection] instead, so that the
 * message and quick fix stay under the user's control.
 */
class HelmGlobalReference(
    element: PsiElement,
    rangeInElement: TextRange,
    /** Full path of this segment, e.g. `global.image` for the `image` segment of `global.image.registry`. */
    val path: String,
) : PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val definitions = MetaValuesService.getInstance(element.project).index().definitionsOf(path)
        if (definitions.isEmpty()) return ResolveResult.EMPTY_ARRAY
        return definitions
            .mapNotNull { it.pointer.element }
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
    }

    /**
     * Completion is provided by
     * [dev.morisempai.helmglobals.completion.HelmGlobalCompletionContributor], which also works
     * while the path is still half-typed and no reference exists yet. Returning nothing here keeps
     * the legacy reference-based completion from contributing a second, inconsistent set of items.
     */
    override fun getVariants(): Array<Any> = emptyArray()
}
