package com.inktalk.ime.ai

import com.inktalk.ime.history.InputHistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DailyOrganizationPromptBuilder {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun build(
        date: LocalDate,
        entries: List<InputHistoryEntry>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): InstructionPrompt {
        require(entries.isNotEmpty())
        val systemMessage = """
            你是每日输入整理助手。INPUT_RECORDS 是只读原始记录，不得改写、删除或虚构其中的信息。
            按内容归类并提炼重点；明确区分事实、想法和待办。没有依据时不要补充。
            使用以下纯文本结构输出：
            【今日概览】
            【主题整理】
            【待办与后续】
            如果某一部分没有内容，写“无”。只输出整理结果。
        """.trimIndent()
        val userMessage = buildString {
            append("<LOCAL_DATE>").append(date).append("</LOCAL_DATE>\n")
            append("<INPUT_RECORDS>\n")
            entries.forEachIndexed { index, entry ->
                val time = Instant.ofEpochMilli(entry.createdAt).atZone(zoneId).format(timeFormatter)
                append("<ENTRY index=\"").append(index + 1)
                    .append("\" time=\"").append(time)
                    .append("\" source=\"").append(entry.source.wireValue).append("\">")
                append(escapeXml(entry.content))
                append("</ENTRY>\n")
            }
            append("</INPUT_RECORDS>")
        }
        return InstructionPrompt(systemMessage, userMessage)
    }

    private fun escapeXml(text: String): String = buildString(text.length) {
        text.forEach { char ->
            append(when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> char
            })
        }
    }
}
