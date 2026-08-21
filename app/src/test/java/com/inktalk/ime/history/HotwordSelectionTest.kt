package com.inktalk.ime.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotwordSelectionTest {
    @Test fun adjacentAndSeparateSelectionsBecomeSeparateTerms() {
        val units = HotwordSelection.units("百炼 和 GPT-5")
        val spans = HotwordSelection.spans(units, setOf(0, 1, 5, 6, 7, 8, 9))
        assertEquals(listOf("百炼", "GPT-5"), spans.map { it.term })
    }

    @Test fun emojiIsOneVisibleUnit() {
        assertEquals(listOf("A", "👍🏽", "中"), HotwordSelection.units("A👍🏽中").map { it.text })
    }

    @Test fun correctionRequiresShortChangedText() {
        assertEquals("百炼", CorrectionCandidateDetector.detect("百练", "百炼")?.newTerm)
        assertNull(CorrectionCandidateDetector.detect("相同", "相同"))
        assertNull(CorrectionCandidateDetector.detect("词", "a".repeat(80)))
    }
}
