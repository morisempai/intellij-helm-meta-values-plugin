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

    fun testShowsNothingForAMapping() {
        doTestProvider(
            "values.yaml",
            "image: {{ toYaml .Values.global.image }}",
            HelmGlobalsInlayProvider(),
        )
    }
}
