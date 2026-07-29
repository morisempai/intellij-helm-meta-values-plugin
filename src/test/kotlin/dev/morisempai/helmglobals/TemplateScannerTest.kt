package dev.morisempai.helmglobals

import dev.morisempai.helmglobals.psi.TemplateScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateScannerTest {

    @Test
    fun `finds a single path in a delimited expression`() {
        val references = TemplateScanner.scan("""app.{{ .Values.global.baseDomain }}""")
        assertEquals(1, references.size)
        assertEquals("global.baseDomain", references[0].path)
        assertEquals("global.baseDomain", references[0].pathRange.substring("""app.{{ .Values.global.baseDomain }}"""))
    }

    @Test
    fun `segment ranges line up with the segments`() {
        val text = """{{ .Values.global.image.registry }}"""
        val reference = TemplateScanner.scan(text).single()
        val segments = reference.segmentRanges.map { it.substring(text) }
        assertEquals(listOf("global", "image", "registry"), segments)
        assertEquals("global.image", reference.pathUpTo(1))
    }

    @Test
    fun `accepts the dollar prefixed root`() {
        assertEquals("global.registry", TemplateScanner.scan("""{{ $.Values.global.registry }}""").single().path)
    }

    @Test
    fun `finds every path in one expression`() {
        val references = TemplateScanner.scan("""{{ .Values.global.a }}-{{ .Values.global.b }}""")
        assertEquals(listOf("global.a", "global.b"), references.map { it.path })
    }

    @Test
    fun `survives a pipeline`() {
        val reference = TemplateScanner.scan("""{{ .Values.global.replicaCount | default 2 }}""").single()
        assertEquals("global.replicaCount", reference.path)
    }

    @Test
    fun `ignores text without an expression`() {
        assertTrue(TemplateScanner.scan("plain: value").isEmpty())
        assertTrue(TemplateScanner.scan(".Values.global.registry").isEmpty())
    }

    @Test
    fun `scanBody treats the whole text as an expression body`() {
        val reference = TemplateScanner.scanBody(".Values.global.replicaCount | default 2").single()
        assertEquals("global.replicaCount", reference.path)
    }

    @Test
    fun `isUnder matches the root and its descendants only`() {
        val reference = TemplateScanner.scanBody(".Values.globalish.x").single()
        assertFalse(reference.isUnder("global"))
        assertTrue(TemplateScanner.scanBody(".Values.global.x").single().isUnder("global"))
        assertTrue(TemplateScanner.scanBody(".Values.global").single().isUnder("global"))
    }

    @Test
    fun `structure functions are detected`() {
        assertTrue(TemplateScanner.mentionsStructureFunction(" toYaml .Values.global.image | nindent 4 "))
        assertTrue(TemplateScanner.mentionsStructureFunction("- if .Values.global.enabled "))
    }

    @Test
    fun `a path that merely contains a function name is not a structure use`() {
        assertFalse(TemplateScanner.mentionsStructureFunction(" .Values.global.range "))
        assertFalse(TemplateScanner.mentionsStructureFunction(" .Values.global.ifEnabled "))
        assertFalse(TemplateScanner.mentionsStructureFunction(" .Values.global.registry "))
    }
}
