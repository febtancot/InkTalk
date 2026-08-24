package com.inktalk.ime.keyboard

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinLexiconTest {
    @Test
    fun parsesCandidatesAndNormalizesSeparatorsAndCase() {
        val lexicon = PinyinLexicon.from(
            ByteArrayInputStream("nihao\t你好 你号\nzhongwen\t中文\n".toByteArray())
        )

        assertEquals(listOf("你好", "你号"), lexicon.candidates("Ni'Hao"))
        assertEquals(listOf("中文"), lexicon.candidates("zhongwen"))
        assertTrue(lexicon.candidates("missing").isEmpty())
    }

    @Test
    fun bundledDictionaryContainsRepresentativeChinesePhrases() {
        val dictionary = File("src/main/res/raw/pinyin_dictionary.tsv").readText()

        assertTrue(dictionary.contains("nihao\t你好"))
        assertTrue(dictionary.contains("zhongwen\t中文"))
        assertTrue(dictionary.contains("shurufa\t输入法"))
        assertTrue(dictionary.lineSequence().count() > 30_000)
    }
}
