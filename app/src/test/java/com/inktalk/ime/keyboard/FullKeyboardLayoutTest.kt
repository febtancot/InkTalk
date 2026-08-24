package com.inktalk.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullKeyboardLayoutTest {
    @Test
    fun letterPageProvidesQwertyChineseEnglishAndEditingActions() {
        val chinese = FullKeyboardLayout.rows(
            FullKeyboardLanguage.CHINESE,
            FullKeyboardPage.LETTERS,
            uppercase = false,
        ).flatten()
        val englishUppercase = FullKeyboardLayout.rows(
            FullKeyboardLanguage.ENGLISH,
            FullKeyboardPage.LETTERS,
            uppercase = true,
        ).flatten()

        assertEquals(('a'..'z').map(Char::toString).toSet(), characterValues(chinese).filter {
            it.length == 1 && it[0] in 'a'..'z'
        }.toSet())
        assertTrue("'" in characterValues(chinese))
        assertTrue(chinese.any { it.action == FullKeyboardAction.SwitchLanguage })
        assertTrue(chinese.any { it.action == FullKeyboardAction.SwitchPage })
        assertTrue(chinese.any { it.action == FullKeyboardAction.Space })
        assertTrue(chinese.any { it.action == FullKeyboardAction.Delete })
        assertTrue(englishUppercase.any {
            it.action == FullKeyboardAction.Character("Q")
        })
        assertTrue(englishUppercase.any { it.action == FullKeyboardAction.Shift })
    }

    @Test
    fun numberPageContainsDigitsAndCommonSymbols() {
        val keys = FullKeyboardLayout.rows(
            FullKeyboardLanguage.CHINESE,
            FullKeyboardPage.NUMBERS,
            uppercase = false,
        ).flatten()
        val values = characterValues(keys)

        assertTrue(('0'..'9').all { it.toString() in values })
        assertTrue(listOf("@", "#", "¥", "+", "-", "/", "!", "?", "%").all {
            it in values
        })
        assertTrue(keys.any { it.action == FullKeyboardAction.SwitchPage })
        assertTrue(keys.any { it.action == FullKeyboardAction.Enter })
    }

    private fun characterValues(keys: List<FullKeyboardKey>): List<String> = keys.mapNotNull {
        (it.action as? FullKeyboardAction.Character)?.value
    }
}
