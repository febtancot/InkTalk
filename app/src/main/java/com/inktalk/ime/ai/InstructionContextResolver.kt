package com.inktalk.ime.ai

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** 从宿主编辑器读取可验证的完整文本，并推导选区、全文或空文本目标范围。 */
object InstructionContextResolver {
    const val MAX_DOCUMENT_CHARS = 6_000

    enum class Failure {
        SENSITIVE_FIELD,
        UNAVAILABLE,
        PARTIAL_CONTEXT,
        TOO_LONG,
        INVALID_SELECTION,
    }

    sealed interface Result {
        data class Success(val snapshot: InstructionDocumentSnapshot) : Result
        data class Failed(val reason: Failure) : Result
    }

    fun resolve(
        inputConnection: InputConnection?,
        editorInfo: EditorInfo?,
        editorSessionId: Long,
    ): Result {
        if (isSensitiveInputType(editorInfo?.inputType ?: InputType.TYPE_NULL)) {
            return Result.Failed(Failure.SENSITIVE_FIELD)
        }
        val connection = inputConnection ?: return Result.Failed(Failure.UNAVAILABLE)
        val request = ExtractedTextRequest().apply {
            token = (editorSessionId and Int.MAX_VALUE.toLong()).toInt()
            flags = 0
            hintMaxLines = Int.MAX_VALUE
            hintMaxChars = MAX_DOCUMENT_CHARS + 1
        }
        val extracted = try {
            connection.getExtractedText(request, 0)
        } catch (_: RuntimeException) {
            null
        }
        if (extracted != null &&
            extracted.startOffset == 0 &&
            extracted.partialStartOffset == -1
        ) {
            val fullText = extracted.text?.toString()
                ?: return Result.Failed(Failure.UNAVAILABLE)
            if (fullText.length > MAX_DOCUMENT_CHARS) return Result.Failed(Failure.TOO_LONG)

            val snapshot = InstructionDocumentSnapshot.fromEditor(
                editorSessionId = editorSessionId,
                fullText = fullText,
                selectionStart = extracted.selectionStart,
                selectionEnd = extracted.selectionEnd,
            ) ?: return Result.Failed(Failure.INVALID_SELECTION)
            return Result.Success(snapshot)
        }

        return resolveFromSurroundingText(connection, editorSessionId)
    }

    private fun resolveFromSurroundingText(
        connection: InputConnection,
        editorSessionId: Long,
    ): Result {
        val before: String
        val selected: String
        val after: String
        try {
            before = connection.getTextBeforeCursor(MAX_DOCUMENT_CHARS + 1, 0)
                ?.toString() ?: return Result.Failed(Failure.UNAVAILABLE)
            selected = connection.getSelectedText(0)?.toString().orEmpty()
            after = connection.getTextAfterCursor(MAX_DOCUMENT_CHARS + 1, 0)
                ?.toString() ?: return Result.Failed(Failure.UNAVAILABLE)
        } catch (_: RuntimeException) {
            return Result.Failed(Failure.UNAVAILABLE)
        }

        if (selected.isNotEmpty()) {
            val snapshot = InstructionDocumentSnapshot.fromSurroundingSelection(
                editorSessionId = editorSessionId,
                beforeCursor = before,
                selectedText = selected,
                afterCursor = after,
                maximumContextChars = MAX_DOCUMENT_CHARS,
            ) ?: return Result.Failed(Failure.TOO_LONG)
            return Result.Success(snapshot)
        }
        if (before.isEmpty() && after.isEmpty()) {
            return Result.Success(
                InstructionDocumentSnapshot.fromEditor(editorSessionId, "", 0, 0)!!
            )
        }
        return Result.Failed(Failure.PARTIAL_CONTEXT)
    }

    fun isSensitiveInputType(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
