package com.inktalk.ime.handwriting

import org.junit.Assert.assertEquals
import org.junit.Test

class HandwritingCandidateMergerTest {
    @Test
    fun interleavesChineseAndEnglishCandidatesWithoutContext() {
        val merged = HandwritingCandidateMerger.merge(
            candidatesByLanguage = mapOf(
                HandwritingLanguage.SIMPLIFIED_CHINESE to listOf("你", "几"),
                HandwritingLanguage.ENGLISH_US to listOf("Hi", "N"),
            ),
            preContext = "",
            limit = 4,
        )

        assertEquals(listOf("你", "Hi", "几", "N"), merged)
    }

    @Test
    fun prioritizesEnglishAfterLatinContext() {
        val merged = HandwritingCandidateMerger.merge(
            candidatesByLanguage = mapOf(
                HandwritingLanguage.SIMPLIFIED_CHINESE to listOf("的", "中"),
                HandwritingLanguage.ENGLISH_US to listOf("test", "text"),
            ),
            preContext = "A quick ",
            limit = 3,
        )

        assertEquals(listOf("test", "的", "text"), merged)
    }

    @Test
    fun removesDuplicatesAndHonorsLimit() {
        val merged = HandwritingCandidateMerger.merge(
            candidatesByLanguage = mapOf(
                HandwritingLanguage.SIMPLIFIED_CHINESE to listOf("A", "中"),
                HandwritingLanguage.ENGLISH_US to listOf("A", "B"),
            ),
            preContext = "",
            limit = 2,
        )

        assertEquals(listOf("A", "中"), merged)
    }
}
