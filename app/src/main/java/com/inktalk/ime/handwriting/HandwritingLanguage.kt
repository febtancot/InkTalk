package com.inktalk.ime.handwriting

/** 手写识别语言独立于语音识别模式，避免语音的数字/英文状态误选手写模型。 */
enum class HandwritingLanguage(val languageTag: String) {
    SIMPLIFIED_CHINESE("zh-Hani-CN"),
    ENGLISH_US("en-US"),
}
