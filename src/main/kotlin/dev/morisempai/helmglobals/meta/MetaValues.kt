package dev.morisempai.helmglobals.meta

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.yaml.psi.YAMLKeyValue

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

/**
 * One entry of a mapping being ranged over. Either [scalar] or [fields] carries the value, according
 * to whether it is a leaf or a mapping of its own.
 */
data class MetaEntry(val key: String, val scalar: String?, val fields: Map<String, String>)

/**
 * One definition of a variable, coming from one meta values file.
 * The same [path] can have several [MetaValue]s when more than one meta file is configured.
 */
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

    /**
     * Entries of the mapping at [path], or `null` when it is not a mapping. Sorted by key, which is
     * the order Go's `range` visits a map in.
     */
    fun entriesOf(path: String): List<MetaEntry>? {
        if (!isMapping(path)) return null
        return childrenOf(path).sorted().map { key ->
            val childPath = "$path.$key"
            if (isMapping(childPath)) {
                MetaEntry(key, scalar = null, fields = flatten(childPath, "", 0))
            } else {
                MetaEntry(key, scalar = scalarAt(childPath), fields = emptyMap())
            }
        }
    }

    /** Scalar leaves below [path], under names relative to it, so `{{ $value.probe.path }}` resolves. */
    private fun flatten(path: String, prefix: String, depth: Int): Map<String, String> {
        if (depth > MAX_FLATTEN_DEPTH) return emptyMap()
        val fields = LinkedHashMap<String, String>()
        for (child in childrenOf(path)) {
            val childPath = "$path.$child"
            val name = if (prefix.isEmpty()) child else "$prefix.$child"
            if (isMapping(childPath)) {
                val nested = flatten(childPath, name, depth + 1)
                // The mapping itself is bound as well, so a condition on it can be decided.
                fields[name] = if (nested.isEmpty()) "{}" else MetaValueRendering.MAPPING_PLACEHOLDER
                fields += nested
            } else {
                scalarAt(childPath)?.let { fields[name] = it }
            }
        }
        return fields
    }

    private fun scalarAt(path: String): String? =
        definitionsOf(path).firstOrNull { it.isScalar }?.presentableValue

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

        private const val MAX_FLATTEN_DEPTH = 16
    }
}
