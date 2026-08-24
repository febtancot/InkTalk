package com.inktalk.ime.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class HotwordSettingsLayoutTest {
    @Test
    fun compactAndWideLayoutsExposeSeparateCustomAndDefaultLists() {
        listOf(
            "src/main/res/layout/activity_hotword_settings.xml",
            "src/main/res/layout-w840dp/activity_hotword_settings.xml",
        ).forEach { path ->
            val document = layoutDocument(path)
            val customEditor = document.elementById("editHotwordList")
            val defaultList = document.elementById("textDefaultHotwordList")

            assertEquals(
                "@string/hotwords_editor_hint",
                customEditor.getAttributeNS(ANDROID_NAMESPACE, "hint"),
            )
            assertEquals("true", defaultList.getAttributeNS(ANDROID_NAMESPACE, "textIsSelectable"))
            assertNotNull(defaultList.parentNode)
        }
    }

    @Test
    fun wideLayoutKeepsBothListsWithinTheShortInnerScreen() {
        val document = layoutDocument(
            "src/main/res/layout-w840dp/activity_hotword_settings.xml",
        )
        val customEditor = document.elementById("editHotwordList")
        val defaultList = document.elementById("textDefaultHotwordList")
        val defaultScroll = defaultList.parentNode as Element

        assertEquals("120dp", customEditor.getAttributeNS(ANDROID_NAMESPACE, "minHeight"))
        assertEquals("100dp", defaultScroll.getAttributeNS(ANDROID_NAMESPACE, "layout_height"))
    }

    private fun layoutDocument(path: String): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(path))

    private fun Document.elementById(id: String): Element {
        val elements = getElementsByTagName("*")
        return (0 until elements.length)
            .map { elements.item(it) as Element }
            .firstOrNull {
                it.getAttributeNS(ANDROID_NAMESPACE, "id") == "@+id/$id"
            }
            ?: throw AssertionError("Missing $id")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
