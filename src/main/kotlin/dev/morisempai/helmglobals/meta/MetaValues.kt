package dev.morisempai.helmglobals.meta

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * One definition of a variable, coming from one meta values file.
 * The same [path] can have several [MetaValue]s when more than one meta file is configured.
 */
/**
 * A sequence value, kept item by item so a `range` over it can be previewed.
 *
 * A list of scalars is used through the dot — `{{ . }}` — and a list of mappings through its fields
 * — `{{ .name }}` — so both are kept: [items] renders each element, [fields] holds the scalar fields
 * of each element under their dotted names.
 */
data class MetaSequence(
    val items: List<String>,
    val allScalars: Boolean,
    /** Per element, in order: dotted field name to value. Empty for an element that is not a mapping. */
    val fields: List<Map<String, String>>,
) {
    /** Every element is a mapping with something in it, so the body can reach into the element. */
    val allMappings: Boolean get() = fields.isNotEmpty() && fields.all { it.isNotEmpty() }
}

data class MetaValue(
    val path: String,
    /** Rendered value, or `null` when the node is a mapping (i.e. an intermediate node). */
    val presentableValue: String?,
    /** `false` when the node is a mapping and therefore has children rather than a value. */
    val isScalar: Boolean,
    /** File name of the meta values file this definition came from, for display. */
    val sourceName: String,
    /** Comment documenting the key, as described by [MetaDocComments]; `null` when undocumented. */
    val doc: String?,
    val pointer: SmartPsiElementPointer<YAMLKeyValue>,
    /** Non-null when the value is a YAML sequence. */
    val sequence: MetaSequence? = null,
)

class MetaIndex(
    private val byPath: Map<String, List<MetaValue>>,
    private val childrenByParent: Map<String, List<String>>,
    /** Names of every meta file that took part in building this index, in configuration order. */
    val sourceNames: List<String>,
) {
    val isEmpty: Boolean get() = byPath.isEmpty()

    fun definitionsOf(path: String): List<MetaValue> = byPath[path].orEmpty()

    fun contains(path: String): Boolean = byPath.containsKey(path)

    /** Immediate child key names of [parentPath]; pass an empty string for the document root. */
    fun childrenOf(parentPath: String): List<String> = childrenByParent[parentPath].orEmpty()

    /** `true` when at least one meta file defines [path] as a mapping. */
    fun isMapping(path: String): Boolean = definitionsOf(path).any { !it.isScalar }

    /** Documentation for [path], taken from the first meta file that documents it. */
    fun docOf(path: String): String? = definitionsOf(path).firstNotNullOfOrNull { it.doc }

    /** The sequence at [path], from the first meta file that defines it as one. */
    fun sequenceOf(path: String): MetaSequence? = definitionsOf(path).firstNotNullOfOrNull { it.sequence }

    /** Meta files that do *not* define [path], out of all files that contributed to this index. */
    fun sourcesMissing(path: String): List<String> {
        val defined = definitionsOf(path).mapTo(HashSet()) { it.sourceName }
        return sourceNames.filterNot { it in defined }
    }

    /** Longest prefix of [path] that exists in the index, or an empty string when even the first segment is unknown. */
    fun longestKnownPrefix(path: String): String {
        val segments = path.split('.')
        var known = ""
        var current = ""
        for (segment in segments) {
            current = if (current.isEmpty()) segment else "$current.$segment"
            if (!contains(current)) break
            known = current
        }
        return known
    }

    companion object {
        val EMPTY = MetaIndex(emptyMap(), emptyMap(), emptyList())
    }
}
