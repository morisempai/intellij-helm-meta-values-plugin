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

    @Test
    fun `a pipe with nothing on one side is reported`() {
        assertEquals(listOf(TemplateProblemKind.EMPTY_PIPELINE_STAGE), kinds("a: {{ .Values.x | }}"))
        assertEquals(listOf(TemplateProblemKind.EMPTY_PIPELINE_STAGE), kinds("a: {{ | quote }}"))
        assertEquals(listOf(TemplateProblemKind.EMPTY_PIPELINE_STAGE), kinds("a: {{ .Values.x || quote }}"))
        assertEquals(
            listOf(TemplateProblemKind.EMPTY_PIPELINE_STAGE),
            kinds("a: {{ .Values.x | default (.Values.y | ) }}"),
        )
    }

    @Test
    fun `an assignment with no value is reported`() {
        assertEquals(
            listOf(TemplateProblemKind.MISSING_ASSIGNED_VALUE),
            kinds("""{{- ${'$'}name := }}"""),
        )
    }

    @Test
    fun `a block keyword with nothing to act on is reported`() {
        for (keyword in listOf("if", "range", "with")) {
            val problems = TemplateSyntax.problems("a: {{ $keyword }}b{{ end }}")
            assertEquals(keyword, listOf(TemplateProblemKind.MISSING_ARGUMENT), problems.map { it.kind })
            assertEquals(keyword, problems.single().keyword)
        }
        assertEquals(
            listOf(TemplateProblemKind.MISSING_ARGUMENT),
            kinds("{{- if .Values.x }}a{{- else if }}b{{- end }}"),
        )
    }

    @Test
    fun `a keyword that takes no arguments is reported when given one`() {
        assertEquals(
            listOf(TemplateProblemKind.UNEXPECTED_ARGUMENT),
            kinds("{{- range .Values.hosts }}a{{- end .Values.hosts }}"),
        )
        assertEquals(
            listOf(TemplateProblemKind.UNEXPECTED_ARGUMENT),
            kinds("{{- if .Values.x }}a{{- else .Values.y }}b{{- end }}"),
        )
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

    @Test
    fun `pipes and quotes inside strings are not read as syntax`() {
        assertTrue(kinds("""a: {{ .Values.x | replace "|" "," }}""").isEmpty())
        assertTrue(kinds("""a: {{ printf "it's here" }}""").isEmpty())
        assertTrue(kinds("""a: {{ printf "%s=" .Values.x }}""").isEmpty())
    }

    /** A comment runs to its own closing marker, and anything at all may sit inside it. */
    @Test
    fun `a comment is not checked`() {
        assertTrue(kinds("{{/* a stray ) and a lone ' and a | */}}").isEmpty())
        assertTrue(kinds("{{- /* trimmed */ -}}").isEmpty())
        assertTrue(kinds("{{/* the closing }} of nothing */}}\na: b").isEmpty())
        assertEquals(listOf(TemplateProblemKind.UNCLOSED), kinds("{{/* never closed"))
    }
}
