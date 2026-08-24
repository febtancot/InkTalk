package com.inktalk.ime.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotwordCatalogTest {
    @Test
    fun defaultCatalogCoversProductAiInternetAndIeltsTerms() {
        val words = HotwordCatalog.defaultWords

        assertTrue(words.size > 200)
        assertTrue("inktalk" in words)
        assertTrue("OpenAI" in words)
        assertTrue("火山引擎" in words)
        assertTrue("ByteDance" in words)
        assertTrue("IELTS Academic" in words)
        assertTrue("Summary Completion" in words)
        assertEquals(words.size, words.map(String::lowercase).distinct().size)
        assertTrue(words.size <= 5_000)
        assertTrue(HotwordCatalog.defaultStorageText.length < 20_000)
    }

    @Test
    fun parseAcceptsCommasAndLinesAndRemovesCaseInsensitiveDuplicates() {
        val words = HotwordCatalog.parse(
            " InkTalk，OpenAI, IELTS\nopenai\r\nSummary Completion ,, ",
        )

        assertEquals(
            listOf("InkTalk", "OpenAI", "IELTS", "Summary Completion"),
            words,
        )
    }

    @Test
    fun normalizeUsesStableChineseCommaStorage() {
        assertEquals("InkTalk，OpenAI，雅思考试", HotwordCatalog.normalize(
            "InkTalk\nOpenAI, 雅思考试",
        ))
        assertFalse(HotwordCatalog.defaultStorageText.contains('\n'))
    }

    @Test
    fun editorTextUsesOneHotwordPerLine() {
        assertEquals(
            "InkTalk\n火山引擎\nIELTS",
            HotwordCatalog.toEditorText("InkTalk，火山引擎,IELTS"),
        )
    }

    @Test
    fun legacyEmptyValueMigratesToDefaultsButCustomValueIsPreserved() {
        assertEquals(HotwordCatalog.defaultStorageText, HotwordCatalog.migrateLegacy(null))
        assertEquals(HotwordCatalog.defaultStorageText, HotwordCatalog.migrateLegacy("  "))
        assertEquals(
            "Custom，OpenAI",
            HotwordCatalog.migrateLegacy("Custom, OpenAI, openai"),
        )
    }

    @Test fun brandMigrationPreservesEmptyAndOtherCustomWords() {
        assertEquals("", HotwordCatalog.migrateBrandName(""))
        assertEquals("inktalk，Custom", HotwordCatalog.migrateBrandName("InkTalk, Custom"))
    }

    @Test
    fun requestHotwordsStayWithinConservativeTokenBudget() {
        val selected = HotwordCatalog.forRequest(HotwordCatalog.defaultStorageText)

        assertTrue(selected.isNotEmpty())
        assertTrue(selected.size < HotwordCatalog.defaultWords.size)
        assertTrue(selected.sumOf(HotwordCatalog::estimateTokens) <= 80)
        val sourceIndexes = selected.map(HotwordCatalog.defaultWords::indexOf)
        assertEquals(sourceIndexes.sorted(), sourceIndexes)
    }

    @Test
    fun confirmedPriorityHotwordsAreSentBeforeDefaults() {
        val raw = HotwordCatalog.defaultStorageText + "，MyCriticalName"
        val selected = HotwordCatalog.forRequest(raw, priorityRaw = "MyCriticalName")
        assertEquals("MyCriticalName", selected.first())
        assertTrue(selected.sumOf(HotwordCatalog::estimateTokens) <= 80)
    }

    @Test
    fun newestHotwordsArePrependedAndDeduplicated() {
        assertEquals(
            listOf("最新词", "第二个", "旧词"),
            HotwordCatalog.prepend("旧词，第二个", listOf("最新词", "第二个")),
        )
    }

    @Test
    fun customAndDefaultLayersStaySeparateAndVisible() {
        val custom = HotwordCatalog.customFromLegacyCombined(
            "用户新词，OpenAI，另一个词",
        )
        val combined = HotwordCatalog.combineCustomWithDefaults(
            HotwordCatalog.serialize(custom),
        )

        assertEquals(listOf("用户新词", "另一个词"), custom)
        assertEquals(listOf("用户新词", "另一个词"), combined.take(2))
        assertEquals(HotwordCatalog.defaultWords.size + 2, combined.size)
        assertTrue("OpenAI" in combined)
        assertTrue("IELTS Academic" in combined)
    }

    @Test
    fun migrationRestoresDefaultsWhenLegacyListContainsOnlyManualWords() {
        val custom = HotwordCatalog.customFromLegacyCombined("手动热词")
        val combined = HotwordCatalog.combineCustomWithDefaults(
            HotwordCatalog.serialize(custom),
        )

        assertEquals("手动热词", combined.first())
        assertEquals(HotwordCatalog.defaultWords.size + 1, combined.size)
        assertTrue(HotwordCatalog.defaultWords.all { it in combined })
    }
}
