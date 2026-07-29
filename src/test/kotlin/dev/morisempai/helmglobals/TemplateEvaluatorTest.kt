package dev.morisempai.helmglobals

import dev.morisempai.helmglobals.template.TemplateEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemplateEvaluatorTest {

    private val values = mapOf(
        "global.registry" to "registry.dev.corp",
        "protocol" to "https",
        "url" to "example",
        "blank" to "",
    )

    private fun evaluate(body: String) = TemplateEvaluator.evaluate(body) { values[it] }

    @Test
    fun `a bare reference is the value itself`() {
        assertEquals("registry.dev.corp", evaluate(" .Values.global.registry "))
    }

    @Test
    fun `print concatenates values and literals`() {
        assertEquals(
            "\"https://example/v1\"",
            evaluate(""" print .Values.protocol "://" .Values.url "/v1" | quote """),
        )
    }

    @Test
    fun `printf fills its verbs from the arguments`() {
        assertEquals("https://example", evaluate(""" printf "%s://%s" .Values.protocol .Values.url """))
        assertEquals("\"example\"", evaluate(""" printf "%q" .Values.url """))
    }

    @Test
    fun `printf with the wrong number of arguments gives up`() {
        assertNull(evaluate(""" printf "%s://%s" .Values.protocol """))
        assertNull(evaluate(""" printf "%f" .Values.url """))
    }

    @Test
    fun `parenthesised sub-expressions are evaluated first`() {
        assertEquals(
            "\"HTTPS://example\"",
            evaluate(""" print (.Values.protocol | upper) "://" .Values.url | quote """),
        )
    }

    @Test
    fun `pipes apply left to right`() {
        assertEquals("\"REGISTRY.DEV.CORP\"", evaluate(" .Values.global.registry | upper | quote "))
    }

    @Test
    fun `default only replaces an empty value`() {
        assertEquals("registry.dev.corp", evaluate(""" .Values.global.registry | default "fallback" """))
        assertEquals("fallback", evaluate(""" .Values.blank | default "fallback" """))
    }

    @Test
    fun `literal arguments are taken as written`() {
        assertEquals("dev.corp", evaluate(""" .Values.global.registry | trimPrefix "registry." """))
        assertEquals("registry-dev-corp", evaluate(""" .Values.global.registry | replace "." "-" """))
    }

    @Test
    fun `whitespace trimming markers do not confuse the parser`() {
        assertEquals("\"registry.dev.corp\"", evaluate("- .Values.global.registry | quote -"))
    }

    @Test
    fun `an unknown variable makes the whole expression unevaluable`() {
        assertNull(evaluate(""" print .Values.protocol "://" .Values.missing """))
    }

    @Test
    fun `an unmodelled function gives up rather than guessing`() {
        assertNull(evaluate(" .Values.global.registry | b64enc "))
        assertNull(evaluate(" .Values.global.registry | myChartHelper "))
        assertNull(evaluate(""" include "chart.name" . """))
    }

    @Test
    fun `context other than Values is not modelled`() {
        assertNull(evaluate(" .Release.Name "))
        assertNull(evaluate(""" print .Values.protocol .Release.Name """))
    }

    @Test
    fun `control flow is not an expression to render`() {
        assertNull(evaluate(" if .Values.protocol "))
        assertNull(evaluate(" range .Values.global.registry "))
    }
}
