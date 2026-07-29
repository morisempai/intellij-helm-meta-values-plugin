package dev.morisempai.helmglobals.meta

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.yaml.psi.YAMLKeyValue

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
