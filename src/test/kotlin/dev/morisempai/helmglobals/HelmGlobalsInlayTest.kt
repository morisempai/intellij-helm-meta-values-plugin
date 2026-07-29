package dev.morisempai.helmglobals

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
              hosts:
                - a.dev.corp
                - b.dev.corp
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

    fun testPreviewsARangeOverAList() {
        doTestProvider(
            "values.yaml",
            """
            hosts:
            {{- range .Values.global.hosts }}
              - {{ . }}
            /*<# block [  - a.dev.corp] #>*/
            /*<# block [  - b.dev.corp] #>*/
            {{- end }}
            """.trimIndent(),
            HelmGlobalsInlayProvider(),
        )
    }

    fun testAppliesTheLoopBodyToEachElement() {
        doTestProvider(
            "values.yaml",
            """
            hosts:
            {{- range .Values.global.hosts }}
              - host: {{ . | quote }}
                port: {{ .Values.global.port }}/*<# = 8080 #>*/
            /*<# block [  - host: "a.dev.corp"] #>*/
            /*<# block [    port: 8080] #>*/
            /*<# block [  - host: "b.dev.corp"] #>*/
            /*<# block [    port: 8080] #>*/
            {{- end }}
            """.trimIndent(),
            HelmGlobalsInlayProvider(),
        )
    }

    fun testPreviewsARangeUsingAssignedVariables() {
        doTestProvider(
            "values.yaml",
            """
            hosts:
            {{- range ${'$'}i, ${'$'}host := .Values.global.hosts }}
              - id: {{ ${'$'}i }}
                name: {{ ${'$'}host }}
            /*<# block [  - id: 0] #>*/
            /*<# block [    name: a.dev.corp] #>*/
            /*<# block [  - id: 1] #>*/
            /*<# block [    name: b.dev.corp] #>*/
            {{- end }}
            """.trimIndent(),
            HelmGlobalsInlayProvider(),
        )
    }

    fun testShowsNoPreviewWhenTheBodyCannotBeRendered() {
        doTestProvider(
            "values.yaml",
            """
            hosts:
            {{- range .Values.global.hosts }}
              - {{ . | b64enc }}
            {{- end }}
            """.trimIndent(),
            HelmGlobalsInlayProvider(),
        )
    }

    fun testShowsNoPreviewForAnUnknownList() {
        doTestProvider(
            "values.yaml",
            """
            hosts:
            {{- range .Values.global.nope }}
              - {{ . }}
            {{- end }}
            """.trimIndent(),
            HelmGlobalsInlayProvider(),
        )
    }

    fun testShowsNothingForAMapping() {
        doTestProvider(
            "values.yaml",
            "image: {{ toYaml .Values.global.image }}",
            HelmGlobalsInlayProvider(),
        )
    }
}
