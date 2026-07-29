package dev.morisempai.helmglobals

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.morisempai.helmglobals.directive.HelmGlobalsDirectives
import dev.morisempai.helmglobals.inspection.MissingInSomeMetaFilesInspection
import dev.morisempai.helmglobals.inspection.UnknownGlobalVariableInspection
import dev.morisempai.helmglobals.inspection.UnresolvedMetaFileInspection
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings
import dev.morisempai.helmglobals.settings.HelmGlobalsState
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * The `# helm-globals:` directive: an explicit, per-file replacement for the configured meta files,
 * in the spirit of `# yaml-language-server: $schema=...`.
 */
class HelmGlobalsDirectiveTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Convention file, deliberately different from the ones the directives point at.
        myFixture.addFileToProject(
            ".helm-globals.yaml",
            """
            global:
              registry: convention.corp
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "meta/dev.yaml",
            """
            global:
              registry: registry.dev.corp
              baseDomain: dev.corp.io
            service:
              port: 8080
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "meta/shared.yaml",
            """
            global:
              team: platform
            """.trimIndent(),
        )
    }

    override fun tearDown() {
        try {
            HelmGlobalsSettings.getInstance(project).loadState(HelmGlobalsState())
        } finally {
            super.tearDown()
        }
    }

    // ---- parsing ---------------------------------------------------------------------------

    fun testParsesABarePath() {
        val directive = HelmGlobalsDirectives.of("# helm-globals: meta/dev.yaml\n")!!
        assertEquals(listOf("meta/dev.yaml"), directive.metaPaths.map { it.text })
        assertNull(directive.root)
    }

    fun testParsesKeyedTokensAndSeveralPaths() {
        val directive = HelmGlobalsDirectives.of(
            "# helm-globals: \$meta=a.yaml, \$meta=b.yaml \$root=global\n"
        )!!
        assertEquals(listOf("a.yaml", "b.yaml"), directive.metaPaths.map { it.text })
        assertEquals("global", directive.root?.text)
    }

    fun testRangesPointAtTheValueItself() {
        val text = "# helm-globals: \$meta=meta/dev.yaml\n"
        val declared = HelmGlobalsDirectives.of(text)!!.metaPaths.single()
        assertEquals("meta/dev.yaml", declared.range.substring(text))
    }

    fun testTextWithoutADirectiveParsesToNothing() {
        assertNull(HelmGlobalsDirectives.of("registry: {{ .Values.global.registry }}\n"))
        assertNull(HelmGlobalsDirectives.of("# just a comment\n"))
    }

    // ---- behaviour -------------------------------------------------------------------------

    fun testDirectiveReplacesTheConventionMetaFile() {
        myFixture.configureByText(
            "values.yaml",
            "# helm-globals: meta/dev.yaml\nregistry: {{ .Values.global.<caret> }}",
        )
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue("expected the directive's file, got $items", items.contains("baseDomain"))
    }

    fun testSeveralPathsAreMerged() {
        myFixture.configureByText(
            "values.yaml",
            "# helm-globals: meta/dev.yaml meta/shared.yaml\nx: {{ .Values.global.<caret> }}",
        )
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(items.contains("baseDomain"))
        assertTrue(items.contains("team"))
    }

    fun testDirectiveOptsInAFileTheGlobsDoNotMatch() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText(
            "anything.yaml",
            "# helm-globals: meta/dev.yaml\nx: {{ .Values.global.nope }}",
        )
        assertTrue(descriptions().any { it.contains("global.nope") })
    }

    fun testPathsResolveRelativeToTheFilesOwnDirectory() {
        myFixture.addFileToProject("charts/api/meta.yaml", "global:\n  chartLocal: local\n")
        val values = myFixture.addFileToProject(
            "charts/api/values.yaml",
            """
            # helm-globals: meta.yaml
            a: {{ .Values.global.chartLocal }}
            b: {{ .Values.global.registry }}
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(values.virtualFile)
        myFixture.enableInspections(UnknownGlobalVariableInspection())

        val reported = descriptions()
        // `meta.yaml` next to the file, not the identically named path from the project root.
        assertTrue(reported.none { it.contains("global.chartLocal") })
        assertTrue(reported.any { it.contains("global.registry") })
    }

    fun testRootTokenNarrowsTheScope() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText(
            "values.yaml",
            "# helm-globals: \$meta=meta/dev.yaml \$root=global\nx: {{ .Values.service.nope }}",
        )
        assertTrue(descriptions().none { it.contains("service.nope") })
    }

    fun testEmptyRootTokenWidensTheScopeBackToEverything() {
        HelmGlobalsSettings.getInstance(project).state.variableRoot = "global"
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText(
            "values.yaml",
            "# helm-globals: \$meta=meta/dev.yaml \$root=\nx: {{ .Values.service.nope }}",
        )
        assertTrue(descriptions().any { it.contains("service.nope") })
    }

    fun testCtrlClickResolvesIntoTheDirectivesMetaFile() {
        myFixture.configureByText(
            "values.yaml",
            "# helm-globals: meta/dev.yaml\nregistry: {{ .Values.global.regi<caret>stry }}",
        )
        val resolved = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()
        assertNotNull("expected the reference to resolve", resolved)
        assertEquals("registry", (resolved as YAMLKeyValue).keyText)
        // The convention file also defines global.registry, so the source file proves which won.
        assertEquals("dev.yaml", resolved.containingFile.name)
    }

    fun testCtrlClickResolvesIntoTheSecondOfSeveralMetaFiles() {
        myFixture.configureByText(
            "values.yaml",
            "# helm-globals: meta/dev.yaml meta/shared.yaml\nx: {{ .Values.global.te<caret>am }}",
        )
        val resolved = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()
        assertEquals("team", (resolved as? YAMLKeyValue)?.keyText)
        assertEquals("shared.yaml", resolved?.containingFile?.name)
    }

    fun testComplementaryMetaFilesAreNotReportedByDefault() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText(
            "values.yaml",
            "# helm-globals: meta/dev.yaml meta/shared.yaml\nx: {{ .Values.global.team }}",
        )
        // `team` lives only in shared.yaml, `registry` only in dev.yaml: that is the normal
        // arrangement for complementary files and must stay quiet.
        assertTrue(descriptions().toString(), descriptions().none { it.contains("not defined in") })
    }

    fun testMissingInSomeMetaFilesIsReportedWhenTheInspectionIsEnabled() {
        myFixture.enableInspections(MissingInSomeMetaFilesInspection())
        myFixture.configureByText(
            "values.yaml",
            "# helm-globals: meta/dev.yaml meta/shared.yaml\nx: {{ .Values.global.team }}",
        )
        assertTrue(descriptions().any { it.contains("global.team") && it.contains("dev.yaml") })
    }

    fun testMissingMetaFileIsReported() {
        myFixture.enableInspections(UnresolvedMetaFileInspection())
        myFixture.configureByText("values.yaml", "# helm-globals: meta/nope.yaml\nx: 1")
        assertTrue(descriptions().any { it.contains("meta/nope.yaml") })
    }

    fun testResolvableMetaFileIsNotReported() {
        myFixture.enableInspections(UnresolvedMetaFileInspection())
        myFixture.configureByText("values.yaml", "# helm-globals: meta/dev.yaml\nx: 1")
        assertTrue(descriptions().none { it.contains("not found") })
    }

    private fun descriptions(): List<String> =
        myFixture.doHighlighting().mapNotNull { it.description }
}
