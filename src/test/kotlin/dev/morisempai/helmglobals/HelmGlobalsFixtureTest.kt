package dev.morisempai.helmglobals

import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.morisempai.helmglobals.inspection.UnknownGlobalVariableInspection
import dev.morisempai.helmglobals.meta.MetaValuesService
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings
import dev.morisempai.helmglobals.settings.HelmGlobalsState
import org.jetbrains.yaml.psi.YAMLKeyValue

class HelmGlobalsFixtureTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(META_FILE_NAME, META_CONTENT)
    }

    override fun tearDown() {
        try {
            // The light project is shared between tests; put the settings back to their defaults.
            HelmGlobalsSettings.getInstance(project).loadState(HelmGlobalsState())
        } finally {
            super.tearDown()
        }
    }

    // ---- meta file discovery and indexing -------------------------------------------------

    fun testMetaFileIsFoundByConvention() {
        val names = MetaValuesService.getInstance(project).metaVirtualFiles().map { it.name }
        assertEquals(listOf(META_FILE_NAME), names)
    }

    fun testIndexHoldsNestedPathsAndValues() {
        val index = MetaValuesService.getInstance(project).index()
        assertTrue(index.contains("global"))
        assertTrue(index.contains("global.registry"))
        assertTrue(index.contains("global.image.pullPolicy"))
        assertEquals("registry.dev.corp", index.definitionsOf("global.registry").single().presentableValue)
        assertTrue(index.isMapping("global.image"))
        assertFalse(index.isMapping("global.registry"))
        assertEquals("global.image", index.longestKnownPrefix("global.image.nope"))
    }

    // ---- completion -----------------------------------------------------------------------

    fun testCompletionInsideUnquotedExpression() {
        myFixture.configureByText("values.yaml", "replicas: {{ .Values.global.<caret> }}")
        assertLookupContains("registry", "baseDomain", "replicaCount", "image")
    }

    fun testCompletionInsideQuotedExpression() {
        myFixture.configureByText("values.yaml", """registry: "{{ .Values.global.<caret> }}"""")
        assertLookupContains("registry", "baseDomain", "replicaCount", "image")
    }

    fun testCompletionInsideEmbeddedExpression() {
        myFixture.configureByText("values.yaml", "host: app.{{ .Values.global.<caret> }}")
        assertLookupContains("baseDomain")
    }

    fun testCompletionDescendsIntoNestedMappings() {
        myFixture.configureByText("values.yaml", "policy: {{ .Values.global.image.<caret> }}")
        assertLookupContains("pullPolicy", "pullSecret")
    }

    fun testCompletionOffersTheRootRightAfterValues() {
        myFixture.configureByText("values.yaml", "policy: {{ .Values.<caret> }}")
        assertLookupContains("global")
    }

    fun testCompletionOffersNothingOutsideAnExpression() {
        myFixture.configureByText("values.yaml", "plain: .Values.global.<caret>")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertFalse(items.contains("registry"))
    }

    fun testCompletionIsInertInFilesThatAreNotValuesFiles() {
        myFixture.configureByText("deployment.yaml", "replicas: {{ .Values.global.<caret> }}")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertFalse(items.contains("registry"))
    }

    // ---- references -----------------------------------------------------------------------

    fun testReferenceResolvesToTheMetaKey() {
        myFixture.configureByText("values.yaml", "replicas: {{ .Values.global.repl<caret>icaCount }}")
        val resolved = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()
        assertTrue("expected a YAMLKeyValue but got $resolved", resolved is YAMLKeyValue)
        assertEquals("replicaCount", (resolved as YAMLKeyValue).keyText)
    }

    fun testIntermediateSegmentResolvesToTheMappingKey() {
        myFixture.configureByText("values.yaml", "policy: {{ .Values.global.im<caret>age.pullPolicy }}")
        val resolved = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()
        assertEquals("image", (resolved as? YAMLKeyValue)?.keyText)
    }

    fun testUnknownPathDoesNotResolve() {
        myFixture.configureByText("values.yaml", "x: {{ .Values.global.no<caret>pe }}")
        assertNull(myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve())
    }

    // ---- inspection -----------------------------------------------------------------------

    fun testUnknownVariableIsReported() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("values.yaml", "registry: {{ .Values.global.nope }}")
        assertTrue(descriptions().any { it.contains("global.nope") })
    }

    fun testKnownVariableIsClean() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("values.yaml", "registry: {{ .Values.global.registry }}")
        assertTrue(descriptions().none { it.contains("global.registry") })
    }

    fun testPathsOutsideTheRootAreIgnored() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("values.yaml", "x: {{ .Values.service.port }}")
        assertTrue(descriptions().none { it.contains("service.port") })
    }

    fun testMappingUsedAsAScalarIsReported() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("values.yaml", "image: {{ .Values.global.image }}")
        assertTrue(descriptions().any { it.contains("is an object") })
    }

    fun testMappingPassedToToYamlIsAccepted() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("values.yaml", "image: {{ toYaml .Values.global.image | nindent 2 }}")
        assertTrue(descriptions().none { it.contains("is an object") })
    }

    fun testNonValuesFileIsNotInspected() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("deployment.yaml", "registry: {{ .Values.global.nope }}")
        assertTrue(descriptions().none { it.contains("global.nope") })
    }

    // ---- quick fix ------------------------------------------------------------------------

    fun testQuickFixAddsTheMissingKeyToTheMetaFile() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("values.yaml", "x: {{ .Values.global.newThing }}")

        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text.contains("newThing") }
        assertNotNull("no quick fix offered", fix)
        myFixture.launchAction(fix!!)

        assertTrue(metaFileText().contains("newThing"))
        assertTrue(MetaValuesService.getInstance(project).index().contains("global.newThing"))
    }

    fun testQuickFixCreatesMissingParents() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("values.yaml", "x: {{ .Values.global.tracing.endpoint }}")

        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text.contains("tracing.endpoint") }
        assertNotNull("no quick fix offered", fix)
        myFixture.launchAction(fix!!)

        val index = MetaValuesService.getInstance(project).index()
        assertTrue(index.contains("global.tracing"))
        assertTrue(index.contains("global.tracing.endpoint"))
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun assertLookupContains(vararg expected: String) {
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        for (item in expected) {
            assertTrue("expected '$item' among $items", items.contains(item))
        }
    }

    private fun descriptions(): List<String> =
        myFixture.doHighlighting().mapNotNull { it.description }

    /** In-memory text: the quick fix edits through PSI, which is not flushed to disk in tests. */
    private fun metaFileText(): String {
        val file = MetaValuesService.getInstance(project).metaVirtualFiles().single()
        return PsiManager.getInstance(project).findFile(file)!!.text
    }

    private companion object {
        const val META_FILE_NAME = ".helm-globals.yaml"
        val META_CONTENT = """
            global:
              registry: registry.dev.corp
              baseDomain: dev.corp.io
              replicaCount: 2
              image:
                pullPolicy: IfNotPresent
                pullSecret: regcred
        """.trimIndent()
    }
}
