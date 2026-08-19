package com.inktalk.ime.ai

import com.inktalk.ime.history.InputHistoryEntry
import com.inktalk.ime.history.InputSource
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DailyOrganizationPromptBuilderTest {
    @Test
    fun promptKeepsEveryRawEntryAndEscapesMarkup() {
        val entries = listOf(
            InputHistoryEntry(1, 0, InputSource.VOICE, "第一个想法 <保留>"),
            InputHistoryEntry(2, 60_000, InputSource.HANDWRITING, "待办 & 备注"),
        )

        val prompt = DailyOrganizationPromptBuilder.build(
            date = LocalDate.of(2026, 8, 19),
            entries = entries,
            zoneId = ZoneId.of("UTC"),
        )

        assertTrue(prompt.systemMessage.contains("只读原始记录"))
        assertTrue(prompt.systemMessage.contains("不得改写、删除或虚构"))
        assertTrue(prompt.userMessage.contains("<LOCAL_DATE>2026-08-19</LOCAL_DATE>"))
        assertTrue(prompt.userMessage.contains("第一个想法 &lt;保留&gt;"))
        assertTrue(prompt.userMessage.contains("待办 &amp; 备注"))
        assertTrue(prompt.userMessage.contains("source=\"voice\""))
        assertTrue(prompt.userMessage.contains("source=\"handwriting\""))
    }
}
