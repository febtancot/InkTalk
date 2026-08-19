package com.inktalk.ime.asr

import java.text.Normalizer

/** 将数字口述结果收敛为适合直接输入的阿拉伯数字文本。 */
object NumericSpeechNormalizer {
    private val digitValues = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '幺' to 1,
        '二' to 2, '两' to 2,
        '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val smallUnits = mapOf('十' to 10L, '百' to 100L, '千' to 1_000L)
    private val largeUnits = mapOf('万' to 10_000L, '亿' to 100_000_000L)

    fun normalize(raw: String): String {
        val source = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
        if (source.isEmpty()) return ""

        val percent = source.contains("百分之")
        val valueSource = source.replace("百分之", "")
        val result = StringBuilder()
        var index = 0
        while (index < valueSource.length) {
            val char = valueSource[index]
            when {
                char.isDigit() -> result.append(char)
                isChineseNumberChar(char) -> {
                    val start = index
                    while (index + 1 < valueSource.length &&
                        isChineseNumberChar(valueSource[index + 1])
                    ) index++
                    result.append(parseChineseNumber(valueSource.substring(start, index + 1)))
                }
                char == '点' || char == '.' -> appendSeparator(result, '.')
                char == '负' || char == '-' -> if (result.isEmpty()) result.append('-')
                char == '/' -> appendSeparator(result, '/')
                char == ':' || char == '：' -> appendSeparator(result, ':')
                char == ',' || char == '，' -> appendSeparator(result, ',')
                char == '%' -> appendSeparator(result, '%')
            }
            index++
        }
        if (percent && result.isNotEmpty() && result.last() != '%') result.append('%')
        return result.toString()
    }

    private fun appendSeparator(result: StringBuilder, separator: Char) {
        if (result.isNotEmpty() && result.last() != separator) result.append(separator)
    }

    private fun isChineseNumberChar(char: Char): Boolean =
        char in digitValues || char in smallUnits || char in largeUnits

    private fun parseChineseNumber(value: String): String {
        if (value.none { it in smallUnits || it in largeUnits }) {
            return value.mapNotNull(digitValues::get).joinToString("")
        }

        var total = 0L
        var section = 0L
        var number = 0L
        value.forEach { char ->
            when {
                char in digitValues -> number = digitValues.getValue(char).toLong()
                char in smallUnits -> {
                    if (number == 0L) number = 1L
                    section += number * smallUnits.getValue(char)
                    number = 0L
                }
                char in largeUnits -> {
                    section += number
                    if (section == 0L) section = 1L
                    total += section * largeUnits.getValue(char)
                    section = 0L
                    number = 0L
                }
            }
        }
        return (total + section + number).toString()
    }
}
