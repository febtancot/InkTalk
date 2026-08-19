package com.inktalk.ime.handwriting

import org.junit.Assert.assertEquals
import org.junit.Test

class HandwritingLanguageTest {
    @Test
    fun simplifiedChineseUsesMainlandHanModelTag() {
        assertEquals("zh-Hani-CN", HandwritingLanguage.SIMPLIFIED_CHINESE.languageTag)
    }

    @Test
    fun englishUsesUnitedStatesModelTag() {
        assertEquals("en-US", HandwritingLanguage.ENGLISH_US.languageTag)
    }
}
