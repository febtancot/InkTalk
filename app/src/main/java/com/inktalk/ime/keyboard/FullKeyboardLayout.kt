package com.inktalk.ime.keyboard

enum class FullKeyboardLanguage { CHINESE, ENGLISH }

enum class FullKeyboardPage { LETTERS, NUMBERS }

sealed interface FullKeyboardAction {
    data class Character(val value: String) : FullKeyboardAction
    data object Shift : FullKeyboardAction
    data object Delete : FullKeyboardAction
    data object SwitchLanguage : FullKeyboardAction
    data object SwitchPage : FullKeyboardAction
    data object Space : FullKeyboardAction
    data object Enter : FullKeyboardAction
}

data class FullKeyboardKey(
    val label: String,
    val action: FullKeyboardAction,
    val weight: Float = 1f,
    val accessibilityLabel: String = label,
)

/** 可测试的全键盘键位定义；视图只负责渲染，不持有输入连接。 */
object FullKeyboardLayout {
    fun rows(
        language: FullKeyboardLanguage,
        page: FullKeyboardPage,
        uppercase: Boolean,
    ): List<List<FullKeyboardKey>> = when (page) {
        FullKeyboardPage.LETTERS -> letterRows(language, uppercase)
        FullKeyboardPage.NUMBERS -> numberAndSymbolRows()
    }

    private fun letterRows(
        language: FullKeyboardLanguage,
        uppercase: Boolean,
    ): List<List<FullKeyboardKey>> {
        fun letters(value: String): List<FullKeyboardKey> = value.map { raw ->
            val text = if (uppercase && language == FullKeyboardLanguage.ENGLISH) {
                raw.uppercaseChar().toString()
            } else {
                raw.toString()
            }
            FullKeyboardKey(text, FullKeyboardAction.Character(text))
        }
        val thirdRowLeading = if (language == FullKeyboardLanguage.CHINESE) {
            FullKeyboardKey("'", FullKeyboardAction.Character("'"), 1.3f, "拼音分隔符")
        } else {
            FullKeyboardKey("⇧", FullKeyboardAction.Shift, 1.3f, "切换大小写")
        }
        val languageLabel = if (language == FullKeyboardLanguage.CHINESE) "中" else "EN"
        val comma = if (language == FullKeyboardLanguage.CHINESE) "，" else ","
        val period = if (language == FullKeyboardLanguage.CHINESE) "。" else "."
        return listOf(
            letters("qwertyuiop"),
            letters("asdfghjkl"),
            listOf(thirdRowLeading) + letters("zxcvbnm") + FullKeyboardKey(
                "⌫",
                FullKeyboardAction.Delete,
                1.3f,
                "删除",
            ),
            listOf(
                FullKeyboardKey(
                    languageLabel,
                    FullKeyboardAction.SwitchLanguage,
                    1.2f,
                    if (language == FullKeyboardLanguage.CHINESE) "切换到英文" else "切换到中文",
                ),
                FullKeyboardKey("123", FullKeyboardAction.SwitchPage, 1.2f, "数字和符号"),
                FullKeyboardKey(comma, FullKeyboardAction.Character(comma)),
                FullKeyboardKey("空格", FullKeyboardAction.Space, 3f),
                FullKeyboardKey(period, FullKeyboardAction.Character(period)),
                FullKeyboardKey("↵", FullKeyboardAction.Enter, 1.3f, "回车"),
            ),
        )
    }

    private fun numberAndSymbolRows(): List<List<FullKeyboardKey>> {
        fun characters(values: List<String>): List<FullKeyboardKey> = values.map { value ->
            FullKeyboardKey(value, FullKeyboardAction.Character(value))
        }
        return listOf(
            characters((1..9).map(Int::toString) + "0"),
            characters(listOf("@", "#", "¥", "_", "&", "-", "+", "(", ")", "/")),
            characters(listOf("*", "\"", "'", ":", ";", "!", "?", "%")) + FullKeyboardKey(
                "⌫",
                FullKeyboardAction.Delete,
                2f,
                "删除",
            ),
            listOf(
                FullKeyboardKey("ABC", FullKeyboardAction.SwitchPage, 1.4f, "返回字母键盘"),
                FullKeyboardKey(",", FullKeyboardAction.Character(",")),
                FullKeyboardKey("空格", FullKeyboardAction.Space, 3f),
                FullKeyboardKey(".", FullKeyboardAction.Character(".")),
                FullKeyboardKey("↵", FullKeyboardAction.Enter, 1.4f, "回车"),
            ),
        )
    }
}
