package dev.morisempai.helmglobals

import com.intellij.openapi.vfs.VirtualFile
import dev.morisempai.helmglobals.meta.MetaIndex

/**
 * Everything a feature needs to analyse one values file: which variables exist, which branch of the
 * tree is in scope, and which meta files a quick fix may write to.
 */
class HelmGlobalsContext(
    val index: MetaIndex,
    /** `null` when every `.Values.*` path is in scope. */
    val root: String?,
    val metaFiles: List<VirtualFile>,
)
