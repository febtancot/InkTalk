package com.inktalk.ime.handwriting

/** 将中文和英文模型的结果交错合并，避免任一模型独占候选栏。 */
object HandwritingCandidateMerger {
    fun merge(
        candidatesByLanguage: Map<HandwritingLanguage, List<String>>,
        preContext: String,
        limit: Int,
    ): List<String> {
        if (limit <= 0) return emptyList()
        val preferredLanguage = if (preContext.lastNonWhitespaceIsLatin()) {
            HandwritingLanguage.ENGLISH_US
        } else {
            HandwritingLanguage.SIMPLIFIED_CHINESE
        }
        val secondaryLanguage = if (preferredLanguage == HandwritingLanguage.ENGLISH_US) {
            HandwritingLanguage.SIMPLIFIED_CHINESE
        } else {
            HandwritingLanguage.ENGLISH_US
        }
        val preferred = candidatesByLanguage[preferredLanguage].orEmpty()
        val secondary = candidatesByLanguage[secondaryLanguage].orEmpty()
        val merged = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val maximumCount = maxOf(preferred.size, secondary.size)

        for (index in 0 until maximumCount) {
            listOf(preferred.getOrNull(index), secondary.getOrNull(index)).forEach { candidate ->
                val text = candidate?.takeIf { it.isNotBlank() } ?: return@forEach
                if (seen.add(text)) merged += text
                if (merged.size == limit) return merged
            }
        }
        return merged
    }

    private fun String.lastNonWhitespaceIsLatin(): Boolean =
        trimEnd().lastOrNull()?.let { character ->
            character in 'A'..'Z' || character in 'a'..'z'
        } == true
}
