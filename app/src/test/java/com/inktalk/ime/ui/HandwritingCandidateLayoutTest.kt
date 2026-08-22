package com.inktalk.ime.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class HandwritingCandidateLayoutTest {
    @Test
    fun emptyCandidateStripKeepsItsReservedHeight() {
        val candidateStrip = layoutDocument().elementById("handwritingCandidateScroll")

        assertEquals("40dp", candidateStrip.getAttributeNS(ANDROID_NAMESPACE, "layout_height"))
        assertEquals("4dp", candidateStrip.getAttributeNS(ANDROID_NAMESPACE, "layout_marginBottom"))
        assertEquals("invisible", candidateStrip.getAttributeNS(ANDROID_NAMESPACE, "visibility"))
    }

    @Test
    fun handwritingHintIsInsidePadAndLanguageToggleIsRemoved() {
        val document = layoutDocument()
        val pad = document.elementById("handwritingPad")
        val hint = document.elementById("textHandwritingHint")

        assertSame(pad.parentNode, hint.parentNode)
        assertEquals("center", hint.getAttributeNS(ANDROID_NAMESPACE, "layout_gravity"))
        assertEquals("@string/handwriting_ready", hint.getAttributeNS(ANDROID_NAMESPACE, "text"))
        assertNull(document.elementByIdOrNull("handwritingLanguageGroup"))
    }

    @Test
    fun microphoneUsesCompactAccessibleSize() {
        val microphone = layoutDocument().elementById("btnMic")

        assertEquals("56dp", microphone.getAttributeNS(ANDROID_NAMESPACE, "layout_width"))
        assertEquals("56dp", microphone.getAttributeNS(ANDROID_NAMESPACE, "layout_height"))
        assertEquals("16dp", microphone.getAttributeNS(ANDROID_NAMESPACE, "padding"))
    }

    @Test
    fun handwritingPanelUsesExpandedDrawingInsets() {
        val panel = layoutDocument().elementById("handwritingPanel")

        assertEquals("6dp", panel.getAttributeNS(ANDROID_NAMESPACE, "paddingStart"))
        assertEquals("2dp", panel.getAttributeNS(ANDROID_NAMESPACE, "paddingTop"))
        assertEquals("6dp", panel.getAttributeNS(ANDROID_NAMESPACE, "paddingEnd"))
    }

    private fun layoutDocument(): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File("src/main/res/layout/ime_voice_panel.xml"))

    private fun Document.elementById(id: String): Element =
        elementByIdOrNull(id) ?: throw AssertionError("Missing $id")

    private fun Document.elementByIdOrNull(id: String): Element? {
        val elements = getElementsByTagName("*")
        return (0 until elements.length)
            .map { elements.item(it) as Element }
            .firstOrNull {
                it.getAttributeNS(ANDROID_NAMESPACE, "id") == "@+id/$id"
            }
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
