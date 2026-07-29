package dev.morisempai.helmglobals.meta

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Reads the documentation attached to a key in a meta values file.
 *
 * The convention is the contiguous `#` block directly above the key:
 * ```yaml
 * # -- Container registry all images are pulled from.
 * # Must be reachable from the cluster nodes.
 * registry: registry.dev.corp
 * ```
 *
 * The leading `--` of the first line is optional and stripped when present, so files annotated for
 * [helm-docs](https://github.com/norwoodj/helm-docs) and files with plain comments both work. A
 * blank line between the comment and the key detaches it. When there is no block above, a trailing
 * comment on the key's own line is used instead.
 */
object MetaDocComments {

    /** Strips the `#`, then an optional helm-docs `--` marker. */
    private val COMMENT_PREFIX = Regex("""^#\s*(?:--\s*)?""")

    /** helm-docs metadata such as `# @default -- 5` or `# @section -- Images`: not prose. */
    private val METADATA_LINE = Regex("""^@\w+""")

    fun of(keyValue: YAMLKeyValue): String? = blockAbove(keyValue) ?: trailing(keyValue)

    /**
     * Walks backwards over the key's siblings, collecting comment lines until a blank line or any
     * other element ends the block.
     *
     * YAML indentation and line breaks are ordinary leaves rather than PsiWhiteSpace, so blankness
     * is decided on the text. A key that is the first child of its mapping has no previous sibling;
     * the walk then continues from the mapping itself, which starts at the same offset.
     */
    private fun blockAbove(keyValue: YAMLKeyValue): String? {
        val lines = ArrayList<String>()
        var element: PsiElement = keyValue
        var newlinesSinceComment = 0

        while (true) {
            val previous = element.prevSibling
            if (previous == null) {
                element = element.parent ?: break
                if (element is PsiFile) break
                continue
            }
            when {
                previous is PsiComment -> {
                    lines += body(previous.text)
                    newlinesSinceComment = 0
                }
                previous.text.isBlank() -> {
                    newlinesSinceComment += previous.text.count { it == '\n' }
                    // An empty line between the comment and the key detaches it.
                    if (newlinesSinceComment > 1) break
                }
                else -> break
            }
            element = previous
        }
        return join(lines.asReversed())
    }

    /** A comment on the same line as the key, after its value: `replicaCount: 2  # how many`. */
    private fun trailing(keyValue: YAMLKeyValue): String? {
        var sibling: PsiElement? = keyValue.nextSibling
        while (sibling != null) {
            when {
                sibling is PsiComment -> return join(listOf(body(sibling.text)))
                // Stop at the line break: a comment on the next line documents the next key.
                sibling.text.isBlank() && !sibling.text.contains('\n') -> Unit
                else -> return null
            }
            sibling = sibling.nextSibling
        }
        return null
    }

    private fun body(commentText: String): String = COMMENT_PREFIX.replace(commentText.trim(), "").trim()

    private fun join(lines: List<String>): String? = lines
        .filterNot { METADATA_LINE.containsMatchIn(it) }
        .dropWhile { it.isEmpty() }
        .dropLastWhile { it.isEmpty() }
        .joinToString("\n")
        .takeIf { it.isNotEmpty() }
}
