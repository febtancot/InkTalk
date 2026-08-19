package com.inktalk.ime.ai

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionDocumentTest {
    @Test
    fun derivesSelectionScopeWhileKeepingFullDocumentContext() {
        val snapshot = InstructionDocumentSnapshot.fromEditor(
            editorSessionId = 7,
            fullText = "第一段。第二段。第三段。",
            selectionStart = 4,
            selectionEnd = 8,
        )

        assertNotNull(snapshot)
        assertEquals(InstructionTargetScope.SELECTION, snapshot?.targetScope)
        assertEquals("第一段。第二段。第三段。", snapshot?.fullText)
        assertEquals("第二段。", snapshot?.targetText)
        assertEquals("第一段。", snapshot?.beforeTarget)
        assertEquals("第三段。", snapshot?.afterTarget)
    }

    @Test
    fun derivesInsertAndFullFieldScopes() {
        val insert = InstructionDocumentSnapshot.fromEditor(1, "", 0, 0)
        val full = InstructionDocumentSnapshot.fromEditor(2, "完整文本", 2, 2)

        assertEquals(InstructionTargetScope.INSERT, insert?.targetScope)
        assertEquals(InstructionTargetScope.FULL_FIELD, full?.targetScope)
        assertEquals("完整文本", full?.targetText)
        assertEquals(0, full?.targetStart)
        assertEquals(4, full?.targetEnd)
    }

    @Test
    fun rejectsInvalidSelectionOffsets() {
        assertEquals(null, InstructionDocumentSnapshot.fromEditor(1, "文本", -1, 1))
        assertEquals(null, InstructionDocumentSnapshot.fromEditor(1, "文本", 0, 3))
    }

    @Test
    fun applyRequiresEntireContextAndTargetToRemainUnchanged() {
        val original = InstructionDocumentSnapshot.fromEditor(3, "前文目标后文", 2, 4)!!
        val same = InstructionDocumentSnapshot.fromEditor(3, "前文目标后文", 2, 4)
        val outsideContextChanged = InstructionDocumentSnapshot.fromEditor(3, "新文目标后文", 2, 4)
        val movedSelection = InstructionDocumentSnapshot.fromEditor(3, "前文目标后文", 4, 6)
        val newEditor = InstructionDocumentSnapshot.fromEditor(4, "前文目标后文", 2, 4)

        assertTrue(InstructionApplyPolicy.canApply(original, same))
        assertFalse(InstructionApplyPolicy.canApply(original, outsideContextChanged))
        assertFalse(InstructionApplyPolicy.canApply(original, movedSelection))
        assertFalse(InstructionApplyPolicy.canApply(original, newEditor))
        assertFalse(InstructionApplyPolicy.canApply(original, null))
    }

    @Test
    fun fullFieldResultBecomesStaleWhenCursorMoves() {
        val original = InstructionDocumentSnapshot.fromEditor(9, "完整文本", 1, 1)!!
        val movedCursor = InstructionDocumentSnapshot.fromEditor(9, "完整文本", 3, 3)

        assertEquals(InstructionTargetScope.FULL_FIELD, original.targetScope)
        assertFalse(InstructionApplyPolicy.canApply(original, movedCursor))
    }

    @Test
    fun promptCarriesFullContextButRequestsSelectionOnlyOutput() {
        val snapshot = InstructionDocumentSnapshot.fromEditor(
            editorSessionId = 1,
            fullText = "前文<一>目标&内容后文",
            selectionStart = 5,
            selectionEnd = 10,
        )!!
        val prompt = InstructionPromptBuilder.build(snapshot, "与前文的<语气>一致")

        assertTrue(prompt.systemMessage.contains("selection 模式只输出 TARGET 的替换文本"))
        assertTrue(prompt.userMessage.contains("<BEFORE_TARGET>前文&lt;一&gt;</BEFORE_TARGET>"))
        assertTrue(prompt.userMessage.contains("<TARGET scope=\"selection\">目标&amp;内容</TARGET>"))
        assertTrue(prompt.userMessage.contains("<AFTER_TARGET>后文</AFTER_TARGET>"))
        assertTrue(prompt.userMessage.contains("与前文的&lt;语气&gt;一致"))
    }

    @Test
    fun sensitiveEditorTypesCannotEnterInstructionMode() {
        assertTrue(
            InstructionContextResolver.isSensitiveInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
        )
        assertTrue(
            InstructionContextResolver.isSensitiveInputType(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            )
        )
        assertFalse(
            InstructionContextResolver.isSensitiveInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
            )
        )
    }

    @Test
    fun surroundingSelectionCreatesLocalVerifiedContext() {
        val snapshot = InstructionDocumentSnapshot.fromSurroundingSelection(
            editorSessionId = 12,
            beforeCursor = "前文很长",
            selectedText = "目标",
            afterCursor = "后文很长",
            maximumContextChars = 8,
        )!!

        assertEquals(InstructionTargetScope.SELECTION, snapshot.targetScope)
        assertEquals("目标", snapshot.targetText)
        assertFalse(snapshot.contextIsComplete)
        assertFalse(snapshot.offsetsAreAbsolute)
        assertEquals(8, snapshot.fullText.length)
    }

    @Test
    fun localSelectionSnapshotRemainsVerifiable() {
        val original = InstructionDocumentSnapshot.fromSurroundingSelection(
            13, "前文", "目标", "后文", 100,
        )!!
        val same = InstructionDocumentSnapshot.fromSurroundingSelection(
            13, "前文", "目标", "后文", 100,
        )!!
        val changed = InstructionDocumentSnapshot.fromSurroundingSelection(
            13, "变化", "目标", "后文", 100,
        )!!

        assertTrue(InstructionApplyPolicy.canApply(original, same))
        assertFalse(InstructionApplyPolicy.canApply(original, changed))
    }
}
