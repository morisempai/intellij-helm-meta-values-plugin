package dev.morisempai.helmglobals

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.morisempai.helmglobals.directive.HelmGlobalsDirective
import dev.morisempai.helmglobals.directive.HelmGlobalsDirectives
import dev.morisempai.helmglobals.meta.MetaValuesService
import dev.morisempai.helmglobals.psi.TemplateScanner
import dev.morisempai.helmglobals.template.TemplateSyntax
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings
import java.nio.file.FileSystems
import java.nio.file.InvalidPathException
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

object HelmGlobalsSupport {

    private val CONTEXT_KEY = Key.create<CachedValue<Optional>>("helm.globals.context")

    private val REGIONS_KEY = Key.create<CachedValue<List<TextRange>>>("helm.globals.template.regions")

    private val PROBLEMS_KEY = Key.create<CachedValue<List<TextRange>>>("helm.globals.template.problems")

    private val matcherCache = ConcurrentHashMap<String, PathMatcher>()

    /** CachedValue cannot hold `null`, so the "not a values file" answer is wrapped. */
    private class Optional(val context: HelmGlobalsContext?)

    /**
     * The analysis context for [file], or `null` when it should be left alone: the plugin is off,
     * the file is a meta file itself, no meta values resolve, or the file neither matches a
     * configured glob nor carries a `# helm-globals:` directive.
     *
     * Completion runs against a copy of the file which has no [VirtualFile], hence the use of
     * [PsiFile.getOriginalFile].
     */
    fun contextFor(file: PsiFile): HelmGlobalsContext? {
        val original = file.originalFile
        return CachedValuesManager.getCachedValue(original, CONTEXT_KEY) {
            CachedValueProvider.Result.create(
                Optional(computeContext(original)),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                HelmGlobalsSettings.getInstance(original.project).state,
            )
        }.context
    }

    fun isValuesFile(file: PsiFile): Boolean = contextFor(file) != null

    /** Ranges of the `{{ … }}` regions in [file], cached because highlighting asks per problem. */
    fun templateRegions(file: PsiFile): List<TextRange> {
        val original = file.originalFile
        return CachedValuesManager.getCachedValue(original, REGIONS_KEY) {
            CachedValueProvider.Result.create(TemplateScanner.regions(original.text), original)
        }
    }

    /** Ranges of the malformed template expressions in [file]; cached alongside the regions. */
    fun templateProblemRanges(file: PsiFile): List<TextRange> {
        val original = file.originalFile
        return CachedValuesManager.getCachedValue(original, PROBLEMS_KEY) {
            CachedValueProvider.Result.create(
                TemplateSyntax.problems(original.text).map { it.range },
                original,
            )
        }
    }

    /** The directive written in [file], regardless of whether the file is otherwise analysed. */
    fun directiveOf(file: PsiFile): HelmGlobalsDirective? =
        HelmGlobalsDirectives.of(file.originalFile.text)

    private fun computeContext(file: PsiFile): HelmGlobalsContext? {
        val project = file.project
        val settings = HelmGlobalsSettings.getInstance(project)
        if (!settings.isEnabled) return null

        val virtualFile = file.virtualFile ?: return null

        val service = MetaValuesService.getInstance(project)
        val directive = directiveOf(file)

        // A directive is an explicit opt-in: it selects the meta files and makes this a values file
        // whatever the globs say.
        val metaFiles = if (directive != null && directive.metaPaths.isNotEmpty()) {
            directive.metaPaths.mapNotNull { service.resolvePath(it.text, virtualFile) }.distinct()
        } else {
            service.metaVirtualFiles()
        }

        if (virtualFile in metaFiles) return null
        if (directive == null && !matchesAnyGlob(project, virtualFile, settings.valuesFileGlobs)) return null

        val index = service.indexOf(metaFiles)
        if (index.isEmpty) return null

        // `$root=` with nothing after it deliberately means "no root": analyse every path.
        val declaredRoot = directive?.root
        val root = if (declaredRoot != null) {
            declaredRoot.text.trim().trimStart('.').takeIf(String::isNotEmpty)
        } else {
            settings.variableRoot
        }

        return HelmGlobalsContext(index, root, metaFiles)
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
