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
        "enabled" to "true",
        "debug" to "false",
        "replicas" to "3",
        "zero" to "0",
    )

    private fun evaluate(body: String) = TemplateEvaluator.evaluate(body) { values[it] }

    private fun condition(body: String) = TemplateEvaluator.condition(body) { values[it] }

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

    // ---- conditions -------------------------------------------------------------------------

    @Test
    fun `a condition reads its keyword and trim markers`() {
        assertEquals(true, condition("- if .Values.enabled "))
        assertEquals(true, condition("if .Values.enabled"))
        assertEquals(false, condition("- else if .Values.debug -"))
    }

    @Test
    fun `the zero value of a type is false`() {
        assertEquals(false, condition("if .Values.debug"))
        assertEquals(false, condition("if .Values.blank"))
        assertEquals(false, condition("if .Values.zero"))
        assertEquals(true, condition("if .Values.replicas"))
        assertEquals(true, condition("if .Values.protocol"))
    }

    @Test
    fun `not and empty invert a condition`() {
        assertEquals(true, condition("if not .Values.debug"))
        assertEquals(true, condition("if empty .Values.blank"))
        assertEquals(false, condition("if not .Values.enabled"))
    }

    @Test
    fun `and or combine conditions`() {
        assertEquals(true, condition("if and .Values.enabled .Values.protocol"))
        assertEquals(false, condition("if and .Values.enabled .Values.debug"))
        assertEquals(true, condition("if or .Values.debug .Values.enabled"))
        assertEquals(false, condition("if or .Values.debug .Values.blank"))
    }

    @Test
    fun `eq and ne compare values`() {
        assertEquals(true, condition(""" if eq .Values.protocol "https" """))
        assertEquals(false, condition(""" if eq .Values.protocol "http" """))
        assertEquals(true, condition(""" if ne .Values.protocol "http" """))
        // eq is true when the first argument equals any of the rest.
        assertEquals(true, condition(""" if eq .Values.protocol "http" "https" """))
    }

    @Test
    fun `numeric comparisons are numeric, not lexicographic`() {
        assertEquals(true, condition("if gt .Values.replicas 2"))
        assertEquals(false, condition("if gt .Values.replicas 10"))
        assertEquals(true, condition("if le .Values.replicas 3"))
    }

    @Test
    fun `comparing a number with a string is not decided`() {
        assertNull(condition(""" if gt .Values.replicas "many" """))
    }

    @Test
    fun `a condition on something unknown is undecided, not false`() {
        assertNull(condition("if .Release.IsUpgrade"))
        assertNull(condition("if .Values.missing"))
        assertNull(condition("if hasKey .Values.protocol \"x\""))
        assertNull(condition("if"))
    }
}
