package com.inktalk.ime.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class ExtremeHeightLayoutContractTest {
    @Test
    fun modeButtonsCanMoveBetweenVoiceRowAndTopToolbar() {
        val document = document("ime_voice_panel.xml")
        val voiceRow = document.elementById("voicePurposeButtonRow")
        val purposeControls = document.elementById("voicePurposeControls")
        val purposePill = document.elementById("voicePurposeModePill")
        val microphone = document.elementById("btnMic")
        val voiceActionArea = document.elementById("voiceActionArea")
        val settings = document.elementById("btnSettings")
        val sideSwap = document.elementById("btnExtremeSideSwap")

        document.elementById("topToolbar")
        document.elementById("toolbarActionGroup")
        assertSame(voiceRow, document.elementById("btnHandwritingMode").parentNode)
        assertSame(voiceRow, document.elementById("btnNumericKeypadMode").parentNode)
        assertSame(purposeControls, purposePill.parentNode)
        assertSame(purposeControls, microphone.parentNode)
        assertSame(voiceActionArea, purposeControls.parentNode)
        assertSame(voiceActionArea, settings.parentNode)
        assertSame(voiceActionArea, sideSwap.parentNode)
        assertEquals("gone", sideSwap.getAttributeNS(ANDROID_NAMESPACE, "visibility"))
        assertEquals(
            "78dp",
            document.elementById("voiceActionArea")
                .getAttributeNS(ANDROID_NAMESPACE, "layout_height"),
        )
    }

    @Test
    fun settingsExposeTheExtremeHeightSwitch() {
        val switch = document("activity_settings.xml").elementById("switchExtremeHeight")

        assertEquals(
            "@string/settings_extreme_height_title",
            switch.getAttributeNS(ANDROID_NAMESPACE, "contentDescription"),
        )
    }

    @Test
    fun settingsExposeFullKeyboardSwitchAndImeContainsReplacementPanel() {
        val switch = document("activity_settings.xml").elementById("switchFullKeyboard")
        val ime = document("ime_voice_panel.xml")

        assertEquals(
            "@string/settings_full_keyboard_title",
            switch.getAttributeNS(ANDROID_NAMESPACE, "contentDescription"),
        )
        assertEquals(
            "gone",
            ime.elementById("fullKeyboardPanel")
                .getAttributeNS(ANDROID_NAMESPACE, "visibility"),
        )
        ime.elementById("btnNumericKeypadMode")
    }

    private fun document(fileName: String): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File("src/main/res/layout/$fileName"))

    private fun Document.elementById(id: String): Element {
        val elements = getElementsByTagName("*")
        return (0 until elements.length)
            .map { elements.item(it) as Element }
            .firstOrNull {
                it.getAttributeNS(ANDROID_NAMESPACE, "id") == "@+id/$id"
            } ?: throw AssertionError("Missing $id")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
