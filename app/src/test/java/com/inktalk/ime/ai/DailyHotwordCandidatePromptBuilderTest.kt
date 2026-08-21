package com.inktalk.ime.ai

import com.inktalk.ime.history.InputHistoryEntry
import com.inktalk.ime.history.InputSource
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyHotwordCandidatePromptBuilderTest {
    @Test fun promptRequiresJsonAndEscapesInput() {
        val prompt = DailyHotwordCandidatePromptBuilder.build(
            LocalDate.of(2026, 8, 21),
            listOf(InputHistoryEntry(1, 0, InputSource.VOICE, "InkTalk <百炼>")),
        )
        assertTrue(prompt.systemMessage.contains("只输出 JSON 数组"))
        assertTrue(prompt.userMessage.contains("InkTalk &lt;百炼&gt;"))
    }
}
