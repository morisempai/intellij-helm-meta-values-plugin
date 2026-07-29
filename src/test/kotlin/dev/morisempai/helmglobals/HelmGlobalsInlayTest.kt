package dev.morisempai.helmglobals

import com.intellij.codeInsight.hints.declarative.impl.InlayPresentationEntry
import com.intellij.codeInsight.hints.declarative.impl.InlayPresentationList
import com.intellij.codeInsight.hints.declarative.impl.TextInlayPresentationEntry
import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRendererBase
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase
import dev.morisempai.helmglobals.hints.HelmGlobalsInlayProvider
import dev.morisempai.helmglobals.settings.HelmGlobalsSettings
import dev.morisempai.helmglobals.settings.HelmGlobalsState

class HelmGlobalsInlayTest : DeclarativeInlayHintsProviderTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            ".helm-globals.yaml",
            """
            global:
              registry: registry.dev.corp
              baseDomain: dev.corp.io
              protocol: https
              host: example
              scheme: http
              port: 8080
              ingressEnabled: true
              debug: false
              hosts:
                - a.dev.corp
                - b.dev.corp
              many:
                - a
                - b
                - c
                - d
                - e
              labels:
                zone: eu
                tier: web
              endpoints:
                api:
                  port: 8080
                  probe:
                    path: /healthz
                web:
                  port: 80
              services:
                - name: api
                  port: 8080
                  probe:
                    path: /healthz
                - name: web
                  port: 80
                  probe:
                    path: /
              image:
                pullPolicy: IfNotPresent
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

    fun testShowsResolvedValueForUnquotedExpression() {
        doTestProvider(
            "values.yaml",
            "registry: {{ .Values.global.registry }}/*<# = registry.dev.corp #>*/",
            HelmGlobalsInlayProvider(),
        )
    }

    fun testShowsResolvedValueForEmbeddedExpression() {
        doTestProvider(
            "values.yaml",
            "host: app.{{ .Values.global.baseDomain }}/*<# = dev.corp.io #>*/",
            HelmGlobalsInlayProvider(),
        )
    }

    fun testShowsNothingForAnUnknownPath() {
        doTestProvider(
            "values.yaml",
            "registry: {{ .Values.global.nope }}",
            HelmGlobalsInlayProvider(),
        )
    }

    fun testAppliesAQuotePipeToTheShownValue() {
        doTestProvider(
            "values.yaml",
            """registry: {{ .Values.global.registry | quote }}/*<# = "registry.dev.corp" #>*/""",
            HelmGlobalsInlayProvider(),
        )
    }

    fun testFallsBackToTheRawValueForAnUnmodelledPipe() {
        doTestProvider(
            "values.yaml",
            "registry: {{ .Values.global.registry | b64enc }}/*<# = registry.dev.corp #>*/",
            HelmGlobalsInlayProvider(),
        )
    }

    fun testShowsTheResultOfPrintRatherThanEachVariable() {
        doTestProvider(
            "values.yaml",
            """url: {{ print .Values.global.protocol "://" .Values.global.host "/v1" | quote }}""" +
                """/*<# = "https://example/v1" #>*/""",
            HelmGlobalsInlayProvider(),
        )
    }

    fun testStillListsVariablesWhenTheExpressionCannotBeEvaluated() {
        // Kept short on purpose: a declarative hint truncates a text entry past ~30 characters.
        doTestProvider(
            "values.yaml",
            "url: {{ b64enc .Values.global.scheme .Values.global.port }}" +
                "/*<# scheme = http, port = 8080 #>*/",
            HelmGlobalsInlayProvider(),
        )
    }

    // ---- range previews ----------------------------------------------------------------------
    //
    // These assert the order the editor paints, not the order `doTestProvider` dumps: the dump reads
    // `getBlockElementsInRange`, which sorts block inlays by descending priority, while the painter
    // reads `getBlockElementsForVisualLine`, which sorts the ones above a line by ascending
    // priority. The two are exact opposites, so a dump can pass while the preview reads bottom-up.

    fun testPreviewsARangeOverAList() {
        assertEquals(
            listOf("  - a.dev.corp", "  - b.dev.corp"),
            previewOf(
                """
                hosts:
                {{- range .Values.global.hosts }}
                  - {{ . }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testAppliesTheLoopBodyToEachElement() {
        assertEquals(
            listOf("""  - host: "a.dev.corp"""", "    port: 8080", """  - host: "b.dev.corp"""", "    port: 8080"),
            previewOf(
                """
                hosts:
                {{- range .Values.global.hosts }}
                  - host: {{ . | quote }}
                    port: {{ .Values.global.port }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testPreviewsARangeUsingAssignedVariables() {
        assertEquals(
            listOf("  - id: 0", "    name: a.dev.corp", "  - id: 1", "    name: b.dev.corp"),
            previewOf(
                """
                hosts:
                {{- range ${'$'}i, ${'$'}host := .Values.global.hosts }}
                  - id: {{ ${'$'}i }}
                    name: {{ ${'$'}host }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testPreviewKeepsOnlyTheBranchesTaken() {
        assertEquals(
            listOf("  - name: a.dev.corp", "    ingress: yes", "  - name: b.dev.corp", "    ingress: yes"),
            previewOf(
                """
                hosts:
                {{- range .Values.global.hosts }}
                  - name: {{ . }}
                {{- if .Values.global.ingressEnabled }}
                    ingress: yes
                {{- else }}
                    ingress: no
                {{- end }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testPreviewTakesTheElseBranchWhenTheConditionIsFalse() {
        assertEquals(
            listOf("  - plain: a.dev.corp", "  - plain: b.dev.corp"),
            previewOf(
                """
                hosts:
                {{- range .Values.global.hosts }}
                {{- if .Values.global.debug }}
                  - debug: {{ . }}
                {{- else }}
                  - plain: {{ . }}
                {{- end }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testPreviewsARangeOverAListOfMappings() {
        assertEquals(
            listOf("  - name: api", "    port: 8080", "  - name: web", "    port: 80"),
            previewOf(
                """
                services:
                {{- range .Values.global.services }}
                  - name: {{ .name }}
                    port: {{ .port }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testPreviewsFieldsThroughAnAssignedVariable() {
        assertEquals(
            listOf("  - api:8080", "  - web:80"),
            previewOf(
                """
                services:
                {{- range ${'$'}service := .Values.global.services }}
                  - {{ ${'$'}service.name }}:{{ ${'$'}service.port }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testPreviewsNestedFieldsOfAMappingElement() {
        assertEquals(
            listOf("  - api: /healthz", "  - web: /"),
            previewOf(
                """
                services:
                {{- range .Values.global.services }}
                  - {{ .name }}: {{ .probe.path }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testShowsNoPreviewForAFieldTheElementDoesNotHave() {
        assertEmpty(
            previewOf(
                """
                services:
                {{- range .Values.global.services }}
                  - {{ .nope }}
                {{- end }}
                """.trimIndent()
            )
        )
    }

    fun testPreviewsARangeOverAMappingOfMappings() {
        assertEquals(
            listOf("  - name: api", "    port: 8080", "  - name: web", "    port: 80"),
            previewOf(
                """
                endpoints:
                {{- range ${'$'}key, ${'$'}value := .Values.global.endpoints }}
                  - name: {{ ${'$'}key }}
                    port: {{ ${'$'}value.port }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testPreviewsARangeOverAMappingOfScalars() {
        // Sorted by key, which is the order Go visits a map in: tier before zone.
        assertEquals(
            listOf("  tier: web", "  zone: eu"),
            previewOf(
                """
                labels:
                {{- range ${'$'}key, ${'$'}value := .Values.global.labels }}
                  {{ ${'$'}key }}: {{ ${'$'}value }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testARangeOverAMappingBindsTheDotToTheValue() {
        assertEquals(
            listOf("  - web", "  - eu"),
            previewOf(
                """
                labels:
                {{- range .Values.global.labels }}
                  - {{ . }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testPreviewsNestedFieldsOfAMappingValue() {
        assertEquals(
            listOf("  api: /healthz"),
            previewOf(
                """
                endpoints:
                {{- range ${'$'}key, ${'$'}value := .Values.global.endpoints }}
                {{- if ${'$'}value.probe }}
                  {{ ${'$'}key }}: {{ ${'$'}value.probe.path }}
                {{- end }}
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    /**
     * The hint position indents to the anchor line, so the text must not carry that indentation
     * again: here the anchor `{{- end }}` sits at column 2 and the body at column 4, so the text is
     * the body minus 2 and lands back at 4 on screen.
     */
    fun testPreviewKeepsTheStructureOfAnIndentedRange() {
        assertEquals(
            listOf(
                "  - name: api",
                "    ports:",
                "      - containerPort: 8080",
                "  - name: web",
                "    ports:",
                "      - containerPort: 80",
            ),
            previewOf(
                """
                spec:
                  containers:
                  {{- range .Values.global.services }}
                    - name: {{ .name }}
                      ports:
                        - containerPort: {{ .port }}
                  {{- end }}
                """.trimIndent()
            ),
        )
    }

    /** Twelve lines fit; the fifth entry would not, so it is dropped whole and counted. */
    fun testPreviewStopsAtAnEntryBoundary() {
        assertEquals(
            listOf(
                "  - name: a", "    type: host", "    ready: true",
                "  - name: b", "    type: host", "    ready: true",
                "  - name: c", "    type: host", "    ready: true",
                "  - name: d", "    type: host", "    ready: true",
                "… 1 more entry",
            ),
            previewOf(
                """
                hosts:
                {{- range .Values.global.many }}
                  - name: {{ . }}
                    type: host
                    ready: true
                {{- end }}
                """.trimIndent()
            ),
        )
    }

    fun testShowsNoPreviewWhenTheBodyCannotBeRendered() {
        assertEmpty(
            previewOf(
                """
                hosts:
                {{- range .Values.global.hosts }}
                  - {{ . | b64enc }}
                {{- end }}
                """.trimIndent()
            )
        )
    }

    fun testShowsNoPreviewForAnUnknownList() {
        assertEmpty(
            previewOf(
                """
                hosts:
                {{- range .Values.global.nope }}
                  - {{ . }}
                {{- end }}
                """.trimIndent()
            )
        )
    }

    fun testShowsNothingForAMapping() {
        doTestProvider(
            "values.yaml",
            "image: {{ toYaml .Values.global.image }}",
            HelmGlobalsInlayProvider(),
        )
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The preview above a range, in the order the editor stacks the hints down the page. */
    private fun previewOf(source: String): List<String> {
        myFixture.configureByText("values.yaml", source)
        myFixture.doHighlighting()
        return paintedBlockHints()
    }

    /** The hint texts above each line, in the order the editor stacks them down the page. */
    private fun paintedBlockHints(): List<String> {
        val editor = myFixture.editor
        return (0 until editor.document.lineCount)
            .flatMap { line -> editor.inlayModel.getBlockElementsForVisualLine(line, true) }
            .map { inlay ->
                (inlay.renderer as DeclarativeInlayRendererBase<*>).presentationLists
                    .flatMap { entriesOf(it) }
                    .filterIsInstance<TextInlayPresentationEntry>()
                    .joinToString("") { it.text }
            }
    }

    /** `getEntries` is public in the bytecode but private to Kotlin, so it is read reflectively. */
    private fun entriesOf(list: InlayPresentationList): List<InlayPresentationEntry> {
        val method = InlayPresentationList::class.java.getMethod("getEntries")
        @Suppress("UNCHECKED_CAST")
        return (method.invoke(list) as Array<InlayPresentationEntry>).toList()
    }
}
