package dev.morisempai.helmglobals.meta

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.morisempai.helmglobals.settings.CONVENTION_META_FILE_NAMES
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the configured meta values file(s) and exposes them as a flat, cached path index.
 *
 * The cache is invalidated on any PSI change (which covers edits to the meta files themselves),
 * on VFS structure changes (a meta file being created or deleted) and on settings changes.
 */
@Service(Service.Level.PROJECT)
class MetaValuesService(private val project: Project) {

    fun index(): MetaIndex = cached().index

    /** Virtual files the index was built from; useful to exclude them from analysis. */
    fun metaVirtualFiles(): List<VirtualFile> = cached().files

    /**
     * Index over an explicit set of meta files, as named by a `# helm-globals:` directive.
     * Memoised per file set, so many values files pointing at the same meta file share one index.
     */
    fun indexOf(files: List<VirtualFile>): MetaIndex {
        if (files.isEmpty()) return MetaIndex.EMPTY
        if (files == cached().files) return cached().index
        return byFileSet().getOrPut(files.map { it.url }) { buildIndex(files) }
    }

    /**
     * Resolves a path written in a directive: absolute, or relative to [near]'s own directory first
     * — as in `# yaml-language-server` — then to the project root and the content roots.
     */
    fun resolvePath(path: String, near: VirtualFile?): VirtualFile? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val nioPath = try {
            Paths.get(trimmed)
        } catch (_: InvalidPathException) {
            return null
        }
        if (nioPath.isAbsolute) return LocalFileSystem.getInstance().findFileByNioFile(nioPath)
        val roots = buildList {
            near?.parent?.let { add(it) }
            addAll(searchRoots())
        }
        return roots.firstNotNullOfOrNull { it.findFileByRelativePath(trimmed) }
            ?.takeIf { it.isValid && !it.isDirectory }
    }

    private fun byFileSet(): MutableMap<List<String>, MetaIndex> =
        CachedValuesManager.getManager(project).getCachedValue(project, BY_FILE_SET_KEY, {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<List<String>, MetaIndex>(),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                HelmGlobalsSettings.getInstance(project).state,
            )
        }, false)

    fun metaYamlFiles(): List<YAMLFile> {
        val psiManager = PsiManager.getInstance(project)
        return metaVirtualFiles().mapNotNull { psiManager.findFile(it) as? YAMLFile }
    }

    private fun cached(): Snapshot {
        val settings = HelmGlobalsSettings.getInstance(project)
        return CachedValuesManager.getManager(project).getCachedValue(project, CACHE_KEY, {
            val files = resolveMetaFiles()
            CachedValueProvider.Result.create(
                Snapshot(files, buildIndex(files)),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                settings.state,
            )
        }, false)
    }

    private fun resolveMetaFiles(): List<VirtualFile> {
        val settings = HelmGlobalsSettings.getInstance(project)
        if (!settings.isEnabled) return emptyList()

        val configured = settings.metaFilePaths
        val files = if (configured.isNotEmpty()) {
            configured.mapNotNull { resolveConfiguredPath(it) }
        } else {
            conventionFiles()
        }
        return files.filter { it.isValid && !it.isDirectory }.distinct()
    }

    private fun resolveConfiguredPath(raw: String): VirtualFile? {
        val path = raw.trim()
        if (path.isEmpty()) return null
        val nioPath = try {
            Paths.get(path)
        } catch (_: InvalidPathException) {
            return null
        }
        if (nioPath.isAbsolute) return LocalFileSystem.getInstance().findFileByNioFile(nioPath)
        return searchRoots().firstNotNullOfOrNull { it.findFileByRelativePath(path) }
    }

    private fun conventionFiles(): List<VirtualFile> = searchRoots().flatMap { root ->
        CONVENTION_META_FILE_NAMES.mapNotNull { name -> root.findChild(name) }
    }

    private fun searchRoots(): List<VirtualFile> = buildList {
        project.guessProjectDir()?.let { add(it) }
        addAll(ProjectRootManager.getInstance(project).contentRoots)
    }.distinct()

    private fun buildIndex(files: List<VirtualFile>): MetaIndex {
        if (files.isEmpty()) return MetaIndex.EMPTY

        val psiManager = PsiManager.getInstance(project)
        val byPath = LinkedHashMap<String, MutableList<MetaValue>>()
        val children = LinkedHashMap<String, LinkedHashSet<String>>()
        val sourceNames = ArrayList<String>()

        for (file in files) {
            val yaml = psiManager.findFile(file) as? YAMLFile ?: continue
            val sourceName = file.name
            sourceNames += sourceName
            for (document in yaml.documents) {
                val root = document.topLevelValue as? YAMLMapping ?: continue
                collect(root, "", sourceName, byPath, children, 0)
            }
        }

        if (byPath.isEmpty()) return MetaIndex(emptyMap(), emptyMap(), sourceNames)
        return MetaIndex(byPath, children.mapValues { (_, v) -> v.toList() }, sourceNames)
    }

    private fun collect(
        mapping: YAMLMapping,
        prefix: String,
        sourceName: String,
        byPath: MutableMap<String, MutableList<MetaValue>>,
        children: MutableMap<String, LinkedHashSet<String>>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val pointerManager = SmartPointerManager.getInstance(project)
        for (keyValue in mapping.keyValues) {
            val key = keyValue.keyText.trim()
            if (key.isEmpty()) continue
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            children.getOrPut(prefix) { LinkedHashSet() }.add(key)

            val value = keyValue.value
            val isScalar = value !is YAMLMapping
            byPath.getOrPut(path) { ArrayList(1) } += MetaValue(
                path = path,
                presentableValue = if (isScalar) renderValue(value) else null,
                isScalar = isScalar,
                sourceName = sourceName,
                doc = MetaDocComments.of(keyValue),
                pointer = pointerManager.createSmartPsiElementPointer<YAMLKeyValue>(keyValue),
                sequence = (value as? YAMLSequence)?.let(::readSequence),
            )

            if (value is YAMLMapping) collect(value, path, sourceName, byPath, children, depth + 1)
        }
    }

    private fun readSequence(sequence: YAMLSequence): MetaSequence {
        val values = sequence.items.map { it.value }
        return MetaSequence(
            items = values.map { renderValue(it) },
            allScalars = values.all { it is YAMLScalar },
        )
    }

    private fun renderValue(value: YAMLValue?): String = when (value) {
        null -> ""
        is YAMLScalar -> value.textValue
        is YAMLSequence -> value.text.lineSequence().joinToString(" ") { it.trim() }.trim()
        else -> value.text.lineSequence().joinToString(" ") { it.trim() }.trim()
    }

    private class Snapshot(val files: List<VirtualFile>, val index: MetaIndex)

    companion object {
        private const val MAX_DEPTH = 32
        private val CACHE_KEY = Key.create<CachedValue<Snapshot>>("helm.globals.meta.index")
        private val BY_FILE_SET_KEY =
            Key.create<CachedValue<MutableMap<List<String>, MetaIndex>>>("helm.globals.meta.index.by.file.set")

        fun getInstance(project: Project): MetaValuesService = project.service()
    }
}
