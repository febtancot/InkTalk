package com.inktalk.ime.asr

/**
 * 语音输入的识别目标。
 *
 * 双向流式接口原生支持中英文混合识别；英文模式可按用户设置选择实时中英混合，或切换
 * 到支持 en-US 偏好的流式输入接口。数字模式继续在本地只保留、规范化数字内容。
 */
enum class SpeechInputMode(val preferenceValue: String) {
    CHINESE("zh"),
    NUMBER("number"),
    ENGLISH("en");

    fun usesLanguageSpecificEndpoint(strategy: EnglishRecognitionStrategy): Boolean =
        this == ENGLISH && strategy == EnglishRecognitionStrategy.ENGLISH_PRIORITY

    fun languageCode(strategy: EnglishRecognitionStrategy): String? =
        if (usesLanguageSpecificEndpoint(strategy)) "en-US" else null

    fun normalizedFor(strategy: EnglishRecognitionStrategy): SpeechInputMode =
        if (strategy == EnglishRecognitionStrategy.REALTIME_BILINGUAL && this == ENGLISH) {
            CHINESE
        } else {
            this
        }

    fun transformResult(text: String): String = when (this) {
        NUMBER -> NumericSpeechNormalizer.normalize(text)
        CHINESE, ENGLISH -> text
    }

    companion object {
        fun fromPreference(value: String?): SpeechInputMode =
            entries.firstOrNull { it.preferenceValue == value } ?: CHINESE

        fun visibleModes(strategy: EnglishRecognitionStrategy): List<SpeechInputMode> =
            if (strategy == EnglishRecognitionStrategy.REALTIME_BILINGUAL) {
                listOf(CHINESE, NUMBER)
            } else {
                entries
            }
    }
}

enum class EnglishRecognitionStrategy(val preferenceValue: String) {
    REALTIME_BILINGUAL("realtime_bilingual"),
    ENGLISH_PRIORITY("english_priority");

    companion object {
        fun fromPreference(value: String?): EnglishRecognitionStrategy =
            entries.firstOrNull { it.preferenceValue == value } ?: REALTIME_BILINGUAL
    }
}
