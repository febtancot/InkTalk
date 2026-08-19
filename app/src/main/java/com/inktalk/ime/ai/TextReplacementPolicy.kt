package com.inktalk.ime.ai

/** AI 结果覆盖前的安全校验，避免光标移动后误删第三方应用中的其他文字。 */
object TextReplacementPolicy {
    fun promptText(selectedText: CharSequence?, fallbackText: String): String =
        (selectedText?.toString()?.takeIf { it.isNotBlank() } ?: fallbackText).trim()

    fun canReplace(textBeforeCursor: CharSequence?, originalText: String): Boolean =
        originalText.isNotEmpty() && textBeforeCursor?.toString() == originalText

    fun canApplyToSelection(currentSelection: CharSequence?, originalSelection: String): Boolean =
        originalSelection.isNotBlank() && currentSelection?.toString() == originalSelection

    fun outputForSelection(
        originalSelection: String,
        result: String,
        replaceOriginal: Boolean,
    ): String = if (replaceOriginal) result else originalSelection + "\n" + result
}
