package dev.morisempai.helmglobals.meta

object MetaValueRendering {

    const val MAX_INLINE_LENGTH: Int = 48

    fun abbreviate(value: String, max: Int = MAX_INLINE_LENGTH): String {
        val singleLine = value.lineSequence().joinToString(" ") { it.trim() }.trim()
        return if (singleLine.length <= max) singleLine else singleLine.take(max - 1) + "…"
    }

    /** Empty string renders as `""` so that "defined but blank" is distinguishable from "absent". */
    fun quoteIfBlank(value: String): String = value.ifEmpty { "\"\"" }

    /**
     * The one value every meta file agrees on, or `null` when the path is a mapping or the files
     * disagree — the cases where there is no single value to put through a pipe chain.
     */
    fun singleScalarValue(definitions: List<MetaValue>): String? {
        val scalars = definitions.filter { it.isScalar }
        if (scalars.isEmpty()) return null
        return scalars.map { it.presentableValue.orEmpty() }.distinct().singleOrNull()
    }

    /**
     * Short one-line rendering of a value across all meta files.
     * With a single source: `registry.dev.corp`.
     * With several: `dev.yaml: registry.dev.corp | prod.yaml: registry.corp`.
     */
    fun inlineSummary(definitions: List<MetaValue>, multipleSources: Boolean): String? {
        val scalars = definitions.filter { it.isScalar }
        if (scalars.isEmpty()) return null

        val distinct = scalars.map { it.presentableValue.orEmpty() }.distinct()
        if (!multipleSources || distinct.size == 1) {
            return abbreviate(quoteIfBlank(distinct.first()))
        }
        return scalars.joinToString(" | ") {
            "${it.sourceName}: ${abbreviate(quoteIfBlank(it.presentableValue.orEmpty()), 24)}"
        }
    }
}
