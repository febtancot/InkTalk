package com.inktalk.ime.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class NumericSpeechNormalizerTest {
    @Test
    fun convertsSpokenDigitSequence() {
        assertEquals("13800138000", NumericSpeechNormalizer.normalize("一三八零零一三八零零零"))
    }

    @Test
    fun convertsChineseUnitsAndDecimal() {
        assertEquals("123.45", NumericSpeechNormalizer.normalize("一百二十三点四五"))
    }

    @Test
    fun preservesUsefulNumericSeparators() {
        assertEquals("2026/8/18", NumericSpeechNormalizer.normalize("2026/8/18"))
        assertEquals("09:30", NumericSpeechNormalizer.normalize("09：30"))
    }

    @Test
    fun convertsPercentageAndFullWidthDigits() {
        assertEquals("50%", NumericSpeechNormalizer.normalize("百分之五十"))
        assertEquals("-12.5", NumericSpeechNormalizer.normalize("负１２点５"))
    }

    @Test
    fun removesNonNumericSpeech() {
        assertEquals("", NumericSpeechNormalizer.normalize("请帮我输入数字"))
    }
}
