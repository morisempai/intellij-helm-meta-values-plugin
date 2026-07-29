package dev.morisempai.helmglobals

import dev.morisempai.helmglobals.template.RangeBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeBlocksTest {

    @Test
    fun `finds a range and the body between it and end`() {
        val text = "hosts:\n{{- range .Values.global.hosts }}\n  - {{ . }}\n{{- end }}\n"
        val block = RangeBlocks.findAll(text).single()
        assertEquals("global.hosts", block.path)
        assertEquals("\n  - {{ . }}\n", text.substring(block.body.startOffset, block.body.endOffset))
        assertEquals("{{- end }}", text.substring(block.end.startOffset, block.end.endOffset))
        assertNull(block.elementVariable)
    }

    @Test
    fun `binds the element variable of an assignment`() {
        val block = RangeBlocks.findAll(
            "{{- range \$host := .Values.global.hosts }}\n- {{ \$host }}\n{{- end }}"
        ).single()
        assertEquals("host", block.elementVariable)
        assertNull(block.keyVariable)
    }

    @Test
    fun `binds both variables of an indexed assignment`() {
        val block = RangeBlocks.findAll(
            "{{- range \$i, \$host := .Values.global.hosts }}\n- {{ \$host }}\n{{- end }}"
        ).single()
        assertEquals("host", block.elementVariable)
        assertEquals("i", block.keyVariable)
    }

    @Test
    fun `a nested if does not close the range early`() {
        val text = """
            {{- range .Values.global.hosts }}
            {{- if .Values.global.enabled }}
              - {{ . }}
            {{- end }}
            {{- end }}
        """.trimIndent()
        val block = RangeBlocks.findAll(text).single()
        // The body has to reach the second `end`, not the one closing the `if`.
        assertTrue(text.substring(block.body.startOffset, block.body.endOffset).contains("- {{ . }}"))
        assertEquals(text.lastIndexOf("{{- end }}"), block.end.startOffset)
    }

    @Test
    fun `a range over something other than Values is not a block to preview`() {
        assertTrue(RangeBlocks.findAll("{{- range .Chart.Maintainers }}\n{{- end }}").isEmpty())
        assertTrue(RangeBlocks.findAll("{{- range \$k, \$v := .Release.Labels }}\n{{- end }}").isEmpty())
    }

    @Test
    fun `an unclosed range yields nothing`() {
        assertTrue(RangeBlocks.findAll("{{- range .Values.global.hosts }}\n  - {{ . }}\n").isEmpty())
    }

    @Test
    fun `several ranges are found independently`() {
        val text = """
            {{- range .Values.global.hosts }}
              - {{ . }}
            {{- end }}
            {{- range .Values.global.zones }}
              - {{ . }}
            {{- end }}
        """.trimIndent()
        assertEquals(listOf("global.hosts", "global.zones"), RangeBlocks.findAll(text).map { it.path })
    }
}
