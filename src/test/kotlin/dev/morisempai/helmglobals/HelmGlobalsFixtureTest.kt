package dev.morisempai.helmglobals

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.morisempai.helmglobals.doc.HelmGlobalDocumentationHtml
import dev.morisempai.helmglobals.doc.HelmGlobalsDocumentationTargetProvider
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

    // ---- doc comments ---------------------------------------------------------------------

    fun testHelmDocsMarkerIsStrippedAndBlockLinesAreJoined() {
        assertEquals(
            "Container registry all images are pulled from.\nMust be reachable from the nodes.",
            docOf("global.registry"),
        )
    }

    fun testPlainCommentBlockIsPickedUpToo() {
        assertEquals("Base DNS domain for ingress hosts.", docOf("global.baseDomain"))
    }

    fun testTrailingCommentIsUsedWhenThereIsNoBlockAbove() {
        assertEquals("how many pods", docOf("global.replicaCount"))
    }

    fun testBlankLineDetachesTheComment() {
        assertNull(docOf("global.detached"))
    }

    fun testHelmDocsMetadataLinesAreNotProse() {
        assertEquals("Pull policy.", docOf("global.image.pullPolicy"))
    }

    fun testMappingKeysCanBeDocumented() {
        assertEquals("Image settings.", docOf("global.image"))
    }

    fun testUndocumentedKeyHasNoDoc() {
        assertNull(docOf("global.image.pullSecret"))
    }

    fun testCompletionShowsAListsItemCount() {
        myFixture.configureByText("values.yaml", "x: {{ .Values.global.<caret> }}")
        val presentation = LookupElementPresentation()
        myFixture.completeBasic().first { it.lookupString == "hosts" }.renderElement(presentation)
        assertEquals("  [2]", presentation.tailText)
    }

    fun testCompletionShowsTheDocInTheRightHandColumn() {
        myFixture.configureByText("values.yaml", "x: {{ .Values.global.<caret> }}")
        val presentation = LookupElementPresentation()
        val element = myFixture.completeBasic().first { it.lookupString == "baseDomain" }
        element.renderElement(presentation)
        assertEquals("Base DNS domain for ingress hosts.", presentation.typeText)
    }

    fun testQuickDocumentationIncludesTheComment() {
        myFixture.configureByText("values.yaml", "x: {{ .Values.global.regi<caret>stry }}")
        // The provider recognising the caret position, and the rendering, are asserted separately:
        // DocumentationResult is write-only, so the HTML cannot be read back off a computed target.
        assertEquals(
            1,
            HelmGlobalsDocumentationTargetProvider()
                .documentationTargets(myFixture.file, myFixture.caretOffset).size,
        )
        val html = HelmGlobalDocumentationHtml.render(
            MetaValuesService.getInstance(project).index(),
            "global.registry",
        )
        assertTrue(html, html.contains("Container registry all images are pulled from."))
        assertTrue(html, html.contains("registry.dev.corp"))
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

    // ---- go template functions --------------------------------------------------------------

    fun testFunctionsAreOfferedAtTheStartOfAnExpression() {
        myFixture.configureByText("values.yaml", "x: {{ pri<caret> }}")
        assertLookupContains("printf", "print", "println")
    }

    fun testFunctionsAreOfferedAfterAPipe() {
        myFixture.configureByText("values.yaml", "x: {{ .Values.global.registry | qu<caret> }}")
        assertLookupContains("quote")
    }

    fun testFunctionsAreOfferedInsideParentheses() {
        myFixture.configureByText("values.yaml", "x: {{ printf \"%s\" (up<caret> }}")
        assertLookupContains("upper")
    }

    fun testControlActionsAreOffered() {
        myFixture.configureByText("values.yaml", "{{- ra<caret> }}")
        assertLookupContains("range")
    }

    fun testFunctionsAreNotOfferedWhereAnArgumentBelongs() {
        myFixture.configureByText("values.yaml", "x: {{ printf \"%s\" qu<caret> }}")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertFalse(items.contains("quote"))
    }

    fun testVariablesStillWinOverFunctionsAfterValues() {
        myFixture.configureByText("values.yaml", "x: {{ .Values.global.<caret> }}")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(items.contains("registry"))
        assertFalse(items.contains("quote"))
    }

    fun testFunctionDocumentationIsOffered() {
        myFixture.configureByText("values.yaml", "x: {{ .Values.global.registry | quo<caret>te }}")
        val targets = HelmGlobalsDocumentationTargetProvider()
            .documentationTargets(myFixture.file, myFixture.caretOffset)
        assertEquals("quote VALUE…", targets.single().computePresentation().presentableText)
    }

    fun testPipedExpressionsAreStillValidated() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText(
            "values.yaml",
            "x: {{ printf \"%s/%s\" .Values.global.registry .Values.global.nope | quote }}",
        )
        val reported = descriptions()
        assertTrue(reported.any { it.contains("global.nope") })
        assertTrue(reported.none { it.contains("global.registry") })
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

    fun testEveryValuesPathIsCheckedByDefault() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("values.yaml", "x: {{ .Values.service.nope }}")
        assertTrue(descriptions().any { it.contains("service.nope") })
    }

    fun testPathsOutsideAConfiguredRootAreIgnored() {
        HelmGlobalsSettings.getInstance(project).state.variableRoot = "global"
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("values.yaml", "x: {{ .Values.service.nope }}")
        assertTrue(descriptions().none { it.contains("service.nope") })
    }

    fun testBareControlLineIsChecked() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText(
            "values.yaml",
            """
            {{- if .Values.global.nope }}
            registry: {{ .Values.global.registry }}
            {{- end }}
            """.trimIndent(),
        )
        assertTrue(descriptions().any { it.contains("global.nope") })
    }

    fun testTemplateInAKeyIsChecked() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("values.yaml", "{{ .Values.global.nope }}: 1")
        assertTrue(descriptions().any { it.contains("global.nope") })
    }

    fun testCommentedOutTemplateIsNotReported() {
        myFixture.enableInspections(UnknownGlobalVariableInspection())
        myFixture.configureByText("values.yaml", "# registry: {{ .Values.global.nope }}")
        assertTrue(descriptions().none { it.contains("global.nope") })
    }

    fun testCompletionOffersEveryTopLevelKeyWithoutARoot() {
        myFixture.configureByText("values.yaml", "x: {{ .Values.<caret> }}")
        assertLookupContains("global", "service")
    }

    fun testCompletionIsRestrictedToAConfiguredRoot() {
        HelmGlobalsSettings.getInstance(project).state.variableRoot = "global"
        myFixture.configureByText("values.yaml", "x: {{ .Values.<caret> }}")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(items.contains("global"))
        assertFalse(items.contains("service"))
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

    // ---- YAML errors provoked by templates ---------------------------------------------------

    fun testControlFlowDoesNotLeaveYamlErrorsInAValuesFile() {
        myFixture.configureByText("values.yaml", RANGE_BLOCK)
        assertTrue(errors().toString(), errors().isEmpty())
    }

    fun testYamlErrorsRemainInFilesThePluginDoesNotAnalyse() {
        myFixture.configureByText("deployment.yaml", RANGE_BLOCK)
        // Proof that the block really is invalid YAML, and that the filter is scoped to values files.
        assertTrue(errors().any { it.contains("Invalid child element") })
    }

    fun testYamlErrorsRemainWhenTheSettingIsOff() {
        HelmGlobalsSettings.getInstance(project).state.hideTemplateSyntaxErrors = false
        myFixture.configureByText("values.yaml", RANGE_BLOCK)
        assertTrue(errors().any { it.contains("Invalid child element") })
    }

    fun testAnErrorAwayFromAnyTemplateIsStillReported() {
        myFixture.configureByText(
            "values.yaml",
            """
            registry: {{ .Values.global.registry }}
            broken:
              - a
             bad: indent
            """.trimIndent(),
        )
        assertTrue(errors().toString(), errors().isNotEmpty())
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

    private fun docOf(path: String): String? =
        MetaValuesService.getInstance(project).index().docOf(path)

    private fun assertLookupContains(vararg expected: String) {
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        for (item in expected) {
            assertTrue("expected '$item' among $items", items.contains(item))
        }
    }

    private fun descriptions(): List<String> =
        myFixture.doHighlighting().mapNotNull { it.description }

    private fun errors(): List<String> = myFixture.doHighlighting()
        .filter { it.severity == HighlightSeverity.ERROR }
        .mapNotNull { it.description }

    /** In-memory text: the quick fix edits through PSI, which is not flushed to disk in tests. */
    private fun metaFileText(): String {
        val file = MetaValuesService.getInstance(project).metaVirtualFiles().single()
        return PsiManager.getInstance(project).findFile(file)!!.text
    }

    private companion object {
        const val META_FILE_NAME = ".helm-globals.yaml"

        /** Valid Helm, invalid YAML: the control lines sit where a mapping key is expected. */
        val RANGE_BLOCK = """
            services:
            {{- range .Values.global.hosts }}
              - {{ . }}
            {{- end }}
        """.trimIndent()
        val META_CONTENT = """
            global:
              # -- Container registry all images are pulled from.
              # Must be reachable from the nodes.
              registry: registry.dev.corp

              # Base DNS domain for ingress hosts.
              baseDomain: dev.corp.io

              replicaCount: 2  # how many pods

              # Detached by the blank line below, so not documentation.

              detached: x

              hosts:
                - a.dev.corp
                - b.dev.corp

              # -- Image settings.
              image:
                # -- Pull policy.
                # @default -- IfNotPresent
                pullPolicy: IfNotPresent
                pullSecret: regcred
            service:
              port: 8080
        """.trimIndent()
    }
}
