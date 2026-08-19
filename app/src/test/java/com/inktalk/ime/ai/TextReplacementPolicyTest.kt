package com.inktalk.ime.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReplacementPolicyTest {
    @Test
    fun allowsExactOriginalImmediatelyBeforeCursor() {
        assertTrue(TextReplacementPolicy.canReplace("需要处理的原文", "需要处理的原文"))
    }

    @Test
    fun rejectsMovedCursorChangedTextAndUnavailableContext() {
        assertFalse(TextReplacementPolicy.canReplace("前缀需要处理的原文", "需要处理的原文"))
        assertFalse(TextReplacementPolicy.canReplace("需要处理的原", "需要处理的原文"))
        assertFalse(TextReplacementPolicy.canReplace(null, "需要处理的原文"))
        assertFalse(TextReplacementPolicy.canReplace("", ""))
    }

    @Test
    fun onlyAppliesAiResultWhenSelectionIsUnchanged() {
        assertTrue(TextReplacementPolicy.canApplyToSelection("选中的文字", "选中的文字"))
        assertFalse(TextReplacementPolicy.canApplyToSelection("已改变的文字", "选中的文字"))
        assertFalse(TextReplacementPolicy.canApplyToSelection(null, "选中的文字"))
        assertFalse(TextReplacementPolicy.canApplyToSelection("", ""))
    }

    @Test
    fun selectedTextTakesPriorityOverCurrentVoiceInput() {
        assertTrue(
            TextReplacementPolicy.promptText("  选中的文字  ", "本次语音内容") ==
                "选中的文字"
        )
        assertTrue(
            TextReplacementPolicy.promptText(null, "  本次语音内容  ") ==
                "本次语音内容"
        )
    }

    @Test
    fun selectedTextResultCanAppendOrReplace() {
        assertTrue(
            TextReplacementPolicy.outputForSelection("原文", "结果", false) ==
                "原文\n结果"
        )
        assertTrue(
            TextReplacementPolicy.outputForSelection("原文", "结果", true) ==
                "结果"
        )
    }
}
