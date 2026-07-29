package dev.morisempai.helmglobals.directive

import com.intellij.openapi.util.TextRange

/** A value read out of a directive, with the range it occupies in the file. */
data class DirectiveValue(val text: String, val range: TextRange)

/**
 * The `# helm-globals:` directive found in a values file, modelled on the `# yaml-language-server:`
 * comment used to attach a JSON schema.
 *
 * ```yaml
 * # helm-globals: meta/dev.yaml
 * # helm-globals: $meta=meta/dev.yaml $meta=meta/shared.yaml $root=global
 * ```
 *
 * A bare token is a meta file path. Paths declared here replace whatever the settings configure,
 * and the directive alone marks the file as a templated values file, so it is analysed even when it
 * does not match the configured globs.
 */
class HelmGlobalsDirective(
    val metaPaths: List<DirectiveValue>,
    /** `$root=`, absent when the directive does not mention it; an empty text means "no root". */
    val root: DirectiveValue?,
)

object HelmGlobalsDirectives {

    const val MARKER: String = "helm-globals:"

    private val LINE = Regex("""(?m)^[ \t]*#[ \t]*helm-globals[ \t]*:(.*)$""")

    /** Tokens are separated by whitespace or commas, so both styles read naturally. */
    private val TOKEN = Regex("""[^\s,]+""")

    private const val META_PREFIX = "${'$'}meta="
    private const val ROOT_PREFIX = "${'$'}root="

    fun of(text: CharSequence): HelmGlobalsDirective? {
        if (!text.contains(MARKER)) return null

        val paths = ArrayList<DirectiveValue>()
        var root: DirectiveValue? = null

        for (line in LINE.findAll(text)) {
            val arguments = line.groups[1] ?: continue
            for (token in TOKEN.findAll(arguments.value)) {
                val start = arguments.range.first + token.range.first
                val raw = token.value
                when {
                    raw.startsWith(ROOT_PREFIX) -> root = value(raw, ROOT_PREFIX.length, start)
                    raw.startsWith(META_PREFIX) -> paths += value(raw, META_PREFIX.length, start)
                    else -> paths += value(raw, 0, start)
                }
            }
        }

        return if (paths.isEmpty() && root == null) null else HelmGlobalsDirective(paths, root)
    }

    private fun value(raw: String, prefixLength: Int, startInText: Int) = DirectiveValue(
        text = raw.substring(prefixLength),
        range = TextRange(startInText + prefixLength, startInText + raw.length),
    )
}
