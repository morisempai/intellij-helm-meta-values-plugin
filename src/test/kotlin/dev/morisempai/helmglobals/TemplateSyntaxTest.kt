package dev.morisempai.helmglobals

import dev.morisempai.helmglobals.template.TemplateProblemKind
import dev.morisempai.helmglobals.template.TemplateSyntax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateSyntaxTest {

    private fun kinds(text: String) = TemplateSyntax.problems(text).map { it.kind }

    // ---- what is genuinely broken ------------------------------------------------------------

    @Test
    fun `an unclosed expression is reported`() {
        assertEquals(listOf(TemplateProblemKind.UNCLOSED), kinds("registry: {{ .Values.registry"))
    }

    @Test
    fun `a stray closing delimiter is reported`() {
        assertEquals(listOf(TemplateProblemKind.STRAY_CLOSE), kinds("registry: .Values.registry }}"))
        assertEquals(
            listOf(TemplateProblemKind.STRAY_CLOSE),
            kinds("a: {{ .Values.a }}\nb: c }}"),
        )
    }

    @Test
    fun `an empty expression is reported`() {
        assertEquals(listOf(TemplateProblemKind.EMPTY), kinds("a: {{ }}"))
        assertEquals(listOf(TemplateProblemKind.EMPTY), kinds("a: {{- -}}"))
    }

    @Test
    fun `unbalanced parentheses are reported`() {
        assertEquals(
            listOf(TemplateProblemKind.UNBALANCED_PARENTHESES),
            kinds("""a: {{ printf "%s" (upper .Values.x }}"""),
        )
        assertEquals(
            listOf(TemplateProblemKind.UNBALANCED_PARENTHESES),
            kinds("""a: {{ upper .Values.x) }}"""),
        )
    }

    @Test
    fun `an unterminated string is reported`() {
        assertEquals(
            listOf(TemplateProblemKind.UNTERMINATED_STRING),
            kinds("""a: {{ printf "%s .Values.x }}"""),
        )
    }

    @Test
    fun `a block that is never closed is reported`() {
        val problems = TemplateSyntax.problems("{{- range .Values.hosts }}\n  - {{ . }}\n")
        assertEquals(listOf(TemplateProblemKind.MISSING_END), problems.map { it.kind })
        assertEquals("range", problems.single().keyword)
    }

    @Test
    fun `an end closing nothing is reported`() {
        assertEquals(listOf(TemplateProblemKind.UNEXPECTED_END), kinds("a: b\n{{- end }}"))
    }

    @Test
    fun `an else outside a block is reported`() {
        assertEquals(listOf(TemplateProblemKind.UNEXPECTED_ELSE), kinds("a: b\n{{- else }}"))
    }

    // ---- what must stay quiet ----------------------------------------------------------------

    @Test
    fun `balanced blocks are accepted`() {
        assertTrue(
            kinds(
                """
                {{- range ${'$'}i, ${'$'}host := .Values.hosts }}
                {{- if .Values.enabled }}
                  - {{ ${'$'}host }}
                {{- else if .Values.other }}
                  - other
                {{- else }}
                  - none
                {{- end }}
                {{- end }}
                """.trimIndent()
            ).isEmpty()
        )
    }

    /** The evaluator gives up on all of these; none of them is a syntax error. */
    @Test
    fun `valid expressions the evaluator cannot handle are not errors`() {
        assertTrue(kinds("""a: {{ include "chart.name" . }}""").isEmpty())
        assertTrue(kinds("a: {{ .Release.Name }}").isEmpty())
        assertTrue(kinds("a: {{ .Values.x | b64enc | quote }}").isEmpty())
        assertTrue(kinds("""a: {{ myChartHelper .Values.x }}""").isEmpty())
        assertTrue(kinds("""{{- ${'$'}full := printf "%s-%s" .Chart.Name .Release.Name }}""").isEmpty())
        assertTrue(kinds("""a: {{ .Values.x | default (printf "%s" .Values.y) }}""").isEmpty())
        assertTrue(kinds("""a: {{ if eq .Values.x "a" }}yes{{ end }}""").isEmpty())
    }

    @Test
    fun `a file without any expression is quiet`() {
        assertTrue(kinds("registry: plain\nport: 8080\n").isEmpty())
    }

    @Test
    fun `braces inside a string do not end the expression early`() {
        // The closing `}}` of the expression is the real one, so nothing is reported.
        assertTrue(kinds("""a: {{ .Values.x | default "{}" }}""").isEmpty())
    }
}
