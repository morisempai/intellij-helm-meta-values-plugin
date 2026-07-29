package dev.morisempai.helmglobals.psi

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import dev.morisempai.helmglobals.HelmGlobalsSupport
import org.jetbrains.yaml.psi.YAMLScalar

class HelmGlobalReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            HelmGlobalReferenceProvider(),
            PsiReferenceRegistrar.DEFAULT_PRIORITY,
        )
    }
}

private class HelmGlobalReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        if (element !is YAMLScalar) return PsiReference.EMPTY_ARRAY

        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        val root = (HelmGlobalsSupport.contextFor(file) ?: return PsiReference.EMPTY_ARRAY).root
        val text = element.text
        val occurrences = HelmTemplates.referencesIn(element)
        if (occurrences.isEmpty()) return PsiReference.EMPTY_ARRAY

        val references = ArrayList<PsiReference>()
        val length = text.length
        for (occurrence in occurrences) {
            if (!occurrence.isUnder(root)) continue
            occurrence.segmentRanges.forEachIndexed { index, range ->
                // Guard against pathological input: ranges must stay inside the host element.
                if (range.endOffset <= length) {
                    references += HelmGlobalReference(element, range, occurrence.pathUpTo(index))
                }
            }
        }
        return if (references.isEmpty()) PsiReference.EMPTY_ARRAY else references.toTypedArray()
    }
}
