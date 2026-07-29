package dev.morisempai.helmglobals.psi

import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Bridges the two shapes the YAML parser produces for Helm template expressions.
 *
 * `host: app.{{ .Values.global.baseDomain }}` and `host: "{{ ... }}"` stay scalars whose text still
 * contains the braces. But an unquoted `replicas: {{ .Values.global.replicaCount }}` is valid YAML
 * flow syntax, so it is parsed as a mapping nested in a mapping:
 *
 * ```
 * YAML hash          "{{ .Values.global.replicaCount }}"
 *   YAML hash        "{ .Values.global.replicaCount }"
 *     plain scalar   ".Values.global.replicaCount"
 * ```
 *
 * The braces therefore never reach the scalar, and the scalar's text has to be treated as a bare
 * template body instead.
 */
object HelmTemplates {

    /** How far up the tree the inferred flow mappings can sit above the scalar. */
    private const val MAX_WRAPPER_DEPTH = 4

    fun referencesIn(scalar: YAMLScalar): List<ValuesReference> {
        val text = scalar.text
        if (text.contains("{{")) return TemplateScanner.scan(text)
        if (enclosingTemplateMapping(scalar) == null) return emptyList()
        return TemplateScanner.scanBody(text)
    }

    /**
     * Absolute file offset just past the `}}` that closes the expression [reference] belongs to.
     * Used to place inline hints after the whole expression rather than in the middle of it.
     */
    fun templateEndOffset(scalar: YAMLScalar, reference: ValuesReference): Int {
        enclosingTemplateMapping(scalar)?.let { return it.textRange.endOffset }
        return scalar.textRange.startOffset + reference.templateRange.endOffset
    }

    /** The outermost inferred `{{ ... }}` flow mapping wrapping [scalar], if there is one. */
    private fun enclosingTemplateMapping(scalar: YAMLScalar): YAMLMapping? {
        var outermost: YAMLMapping? = null
        var current: PsiElement? = scalar.parent
        var depth = 0
        while (current is YAMLMapping && depth < MAX_WRAPPER_DEPTH) {
            if (current.textLength >= 2 && current.text.startsWith("{{")) outermost = current
            current = current.parent
            depth++
        }
        return outermost
    }
}
