package dev.morisempai.helmglobals.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class HelmGlobalsState : BaseState() {
    /** Paths to meta values files, project-relative or absolute. Empty means "use the convention". */
    val metaFilePaths by list<String>()

    /** Globs selecting which files are treated as templated values files. Empty means "use the defaults". */
    val valuesFileGlobs by list<String>()

    /**
     * Path under `.Values` that holds the shared variables, e.g. `global`. Blank — the default —
     * puts every `.Values.*` path in scope.
     */
    var variableRoot by string()

    var enabled by property(true)

    var showInlayValues by property(true)

    /**
     * [BaseState.incrementModificationCount] is protected; the delegated properties above bump it
     * themselves, but in-place edits of the two lists have to do so explicitly.
     */
    fun bumpModificationCount() = incrementModificationCount()
}

/**
 * Project-level configuration, persisted to `.idea/helm-globals.xml` so it can be shared through VCS.
 */
@Service(Service.Level.PROJECT)
@State(name = "HelmGlobalsSettings", storages = [Storage("helm-globals.xml")])
class HelmGlobalsSettings : SimplePersistentStateComponent<HelmGlobalsState>(HelmGlobalsState()) {

    val isEnabled: Boolean get() = state.enabled

    val showInlayValues: Boolean get() = state.showInlayValues

    /** `null` when no root is configured, meaning every `.Values.*` path is analysed. */
    val variableRoot: String?
        get() = state.variableRoot?.trim()?.trimStart('.')?.takeIf { it.isNotEmpty() }

    val metaFilePaths: List<String> get() = state.metaFilePaths.toList()

    val valuesFileGlobs: List<String>
        get() = state.valuesFileGlobs.takeIf { it.isNotEmpty() }?.toList() ?: DEFAULT_VALUES_GLOBS

    fun replaceMetaFilePaths(paths: List<String>) = replaceList(state.metaFilePaths, paths)

    fun replaceValuesFileGlobs(globs: List<String>) = replaceList(state.valuesFileGlobs, globs)

    private fun replaceList(target: MutableList<String>, values: List<String>) {
        if (target == values) return
        target.clear()
        target.addAll(values)
        state.bumpModificationCount()
    }

    companion object {
        fun getInstance(project: Project): HelmGlobalsSettings = project.service()
    }
}

/**
 * Java glob syntax, matched against the project-relative path. `**` does not match zero directories,
 * hence each pattern is present in both a rooted and a nested form.
 */
val DEFAULT_VALUES_GLOBS: List<String> = listOf(
    "values*.y*ml",
    "**/values*.y*ml",
    "*values*.y*ml",
    "**/*values*.y*ml",
)

/** Looked up in the project root and in every content root when no meta file is configured explicitly. */
val CONVENTION_META_FILE_NAMES: List<String> = listOf(
    ".helm-globals.yaml",
    ".helm-globals.yml",
    "helm-globals.yaml",
    "helm-globals.yml",
)
