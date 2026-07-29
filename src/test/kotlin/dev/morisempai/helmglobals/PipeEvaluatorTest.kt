package dev.morisempai.helmglobals

import dev.morisempai.helmglobals.template.PipeEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipeEvaluatorTest {

    private fun apply(body: String, value: String = "registry.dev.corp") =
        PipeEvaluator.apply(body, "global.registry", value)

    @Test
    fun `a bare reference is the value itself`() {
        assertEquals("registry.dev.corp", apply(" .Values.global.registry "))
    }

    @Test
    fun `quote wraps the value`() {
        assertEquals("\"registry.dev.corp\"", apply(" .Values.global.registry | quote "))
    }

    @Test
    fun `pipes apply left to right`() {
        assertEquals("\"REGISTRY.DEV.CORP\"", apply(" .Values.global.registry | upper | quote "))
    }

    @Test
    fun `default only replaces an empty value`() {
        assertEquals("registry.dev.corp", apply(""" .Values.global.registry | default "fallback" """))
        assertEquals("fallback", apply(""" .Values.global.registry | default "fallback" """, value = ""))
    }

    @Test
    fun `trimming functions take their literal argument`() {
        assertEquals("dev.corp", apply(""" .Values.global.registry | trimPrefix "registry." """))
    }

    @Test
    fun `whitespace trimming markers do not confuse the parser`() {
        assertEquals("\"registry.dev.corp\"", apply("- .Values.global.registry | quote -"))
    }

    @Test
    fun `an unmodelled function gives up rather than guessing`() {
        assertNull(apply(" .Values.global.registry | b64enc "))
        assertNull(apply(" .Values.global.registry | printf \"%s\" "))
        assertNull(apply(" .Values.global.registry | myChartHelper "))
    }

    @Test
    fun `a value consumed as an argument is not a pipe`() {
        assertNull(apply(""" printf "%s" .Values.global.registry """))
        assertNull(apply(" required \"set the registry\" .Values.global.registry "))
    }

    @Test
    fun `a longer path is not mistaken for the one asked about`() {
        assertNull(apply(" .Values.global.registryMirror | quote "))
    }
}
