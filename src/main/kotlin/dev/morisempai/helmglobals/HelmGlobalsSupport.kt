package dev.morisempai.helmglobals

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.morisempai.helmglobals.meta.MetaValuesService
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings
import java.nio.file.FileSystems
import java.nio.file.InvalidPathException
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

object HelmGlobalsSupport {

    private val VALUES_FILE_KEY = Key.create<CachedValue<Boolean>>("helm.globals.is.values.file")

    private val matcherCache = ConcurrentHashMap<String, PathMatcher>()

    /**
     * `true` when [file] should be analysed: the plugin is enabled, a non-empty meta values index
     * exists, and the file matches one of the configured globs without being a meta file itself.
     *
     * Completion runs against a copy of the file which has no [VirtualFile], hence the use of
     * [PsiFile.getOriginalFile].
     */
    fun isValuesFile(file: PsiFile): Boolean {
        val original = file.originalFile
        return CachedValuesManager.getCachedValue(original, VALUES_FILE_KEY) {
            CachedValueProvider.Result.create(
                computeIsValuesFile(original),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                HelmGlobalsSettings.getInstance(original.project).state,
            )
        }
    }

    private fun computeIsValuesFile(file: PsiFile): Boolean {
        val project = file.project
        val settings = HelmGlobalsSettings.getInstance(project)
        if (!settings.isEnabled) return false

        val virtualFile = file.virtualFile ?: return false

        val metaService = MetaValuesService.getInstance(project)
        if (virtualFile in metaService.metaVirtualFiles()) return false
        if (metaService.index().isEmpty) return false

        return matchesAnyGlob(project, virtualFile, settings.valuesFileGlobs)
    }

    private fun matchesAnyGlob(project: Project, file: VirtualFile, globs: List<String>): Boolean {
        val projectDir = project.guessProjectDir()
        val relative = projectDir?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
        val candidates = listOf(relative, file.name)
        return globs.any { glob ->
            val matcher = compileGlob(glob) ?: return@any false
            candidates.any { candidate -> matchesSafely(matcher, candidate) }
        }
    }

    private fun matchesSafely(matcher: PathMatcher, candidate: String): Boolean = try {
        matcher.matches(Paths.get(candidate))
    } catch (_: InvalidPathException) {
        false
    }

    private fun compileGlob(glob: String): PathMatcher? {
        val trimmed = glob.trim()
        if (trimmed.isEmpty()) return null
        matcherCache[trimmed]?.let { return it }
        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$trimmed")
        } catch (_: Exception) {
            // A malformed glob typed into the settings must not break highlighting.
            return null
        }
        matcherCache[trimmed] = matcher
        return matcher
    }
}
