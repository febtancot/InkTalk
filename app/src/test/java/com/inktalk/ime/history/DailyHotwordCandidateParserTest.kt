package com.inktalk.ime.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyHotwordCandidateParserTest {
    @Test fun parsesDeduplicatesAndValidatesCandidates() {
        val result = DailyHotwordCandidateParser.parse(
            """[{"term":"InkTalk","reason":"产品名","evidence_count":3},{"term":"inktalk","reason":"重复"},{"term":"   ","reason":"空"}]"""
        )
        assertEquals(1, result.size)
        assertEquals("InkTalk", result.single().term)
        assertEquals(3, result.single().evidenceCount)
    }

    @Test fun malformedPayloadFailsClosed() {
        assertTrue(DailyHotwordCandidateParser.parse("not json").isEmpty())
    }
}
