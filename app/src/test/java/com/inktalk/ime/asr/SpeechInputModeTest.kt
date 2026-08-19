package com.inktalk.ime.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechInputModeTest {
    @Test
    fun restoresPersistedModesWithSafeFallback() {
        assertEquals(SpeechInputMode.NUMBER, SpeechInputMode.fromPreference("number"))
        assertEquals(SpeechInputMode.ENGLISH, SpeechInputMode.fromPreference("en"))
        assertEquals(SpeechInputMode.CHINESE, SpeechInputMode.fromPreference("unknown"))
    }

    @Test
    fun englishEndpointDependsOnSavedStrategy() {
        assertFalse(
            SpeechInputMode.ENGLISH.usesLanguageSpecificEndpoint(
                EnglishRecognitionStrategy.REALTIME_BILINGUAL
            )
        )
        assertEquals(
            null,
            SpeechInputMode.ENGLISH.languageCode(EnglishRecognitionStrategy.REALTIME_BILINGUAL),
        )
        assertTrue(
            SpeechInputMode.ENGLISH.usesLanguageSpecificEndpoint(
                EnglishRecognitionStrategy.ENGLISH_PRIORITY
            )
        )
        assertEquals(
            "en-US",
            SpeechInputMode.ENGLISH.languageCode(EnglishRecognitionStrategy.ENGLISH_PRIORITY),
        )
        assertFalse(
            SpeechInputMode.CHINESE.usesLanguageSpecificEndpoint(
                EnglishRecognitionStrategy.ENGLISH_PRIORITY
            )
        )
        assertFalse(
            SpeechInputMode.NUMBER.usesLanguageSpecificEndpoint(
                EnglishRecognitionStrategy.ENGLISH_PRIORITY
            )
        )
    }

    @Test
    fun englishStrategyRestoresWithRealtimeFallback() {
        assertEquals(
            EnglishRecognitionStrategy.ENGLISH_PRIORITY,
            EnglishRecognitionStrategy.fromPreference("english_priority"),
        )
        assertEquals(
            EnglishRecognitionStrategy.REALTIME_BILINGUAL,
            EnglishRecognitionStrategy.fromPreference("unknown"),
        )
    }

    @Test
    fun realtimeBilingualUsesOneCombinedLanguageEntry() {
        assertEquals(
            listOf(SpeechInputMode.CHINESE, SpeechInputMode.NUMBER),
            SpeechInputMode.visibleModes(EnglishRecognitionStrategy.REALTIME_BILINGUAL),
        )
        assertEquals(
            SpeechInputMode.CHINESE,
            SpeechInputMode.ENGLISH.normalizedFor(
                EnglishRecognitionStrategy.REALTIME_BILINGUAL
            ),
        )
        assertEquals(
            SpeechInputMode.entries,
            SpeechInputMode.visibleModes(EnglishRecognitionStrategy.ENGLISH_PRIORITY),
        )
        assertEquals(
            SpeechInputMode.ENGLISH,
            SpeechInputMode.ENGLISH.normalizedFor(
                EnglishRecognitionStrategy.ENGLISH_PRIORITY
            ),
        )
    }
}
