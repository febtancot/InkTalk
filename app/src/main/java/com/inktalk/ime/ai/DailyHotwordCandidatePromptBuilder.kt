package com.inktalk.ime.ai

import com.inktalk.ime.history.InputHistoryEntry
import java.time.LocalDate

object DailyHotwordCandidatePromptBuilder {
    fun build(date: LocalDate, entries: List<InputHistoryEntry>): InstructionPrompt {
        require(entries.isNotEmpty())
        val system = """
            你是输入法热词候选提取器。只从 INPUT_RECORDS 提取可能需要语音识别热词支持的专有名词、产品名、人名、机构名、英文名称、缩写、型号或重复领域短语。
            不要提取普通虚词、完整句子、敏感信息或没有原文依据的词。
            只输出 JSON 数组；每项格式为 {"term":"词语","reason":"简短原因","evidence_count":1}。最多 20 项；没有候选时输出 []。
        """.trimIndent()
        val user = buildString {
            append("<LOCAL_DATE>").append(date).append("</LOCAL_DATE>\n<INPUT_RECORDS>\n")
            entries.forEach { entry ->
                append("<ENTRY source=\"").append(entry.source.wireValue).append("\">")
                append(entry.content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                append("</ENTRY>\n")
            }
            append("</INPUT_RECORDS>")
        }
        return InstructionPrompt(system, user)
    }
}
