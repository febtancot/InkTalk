package com.inktalk.ime.keyboard

import java.io.InputStream

/** 从随包词典读取“无声调拼音 -> 高频中文候选”，不访问网络。 */
class PinyinLexicon private constructor(
    private val entries: Map<String, List<String>>,
) {
    fun candidates(rawPinyin: String, limit: Int = DEFAULT_LIMIT): List<String> {
        if (limit <= 0) return emptyList()
        return entries[normalize(rawPinyin)].orEmpty().take(limit)
    }

    companion object {
        private const val DEFAULT_LIMIT = 12

        fun from(input: InputStream): PinyinLexicon {
            val entries = LinkedHashMap<String, List<String>>()
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val separator = line.indexOf('\t')
                    if (separator <= 0 || separator == line.lastIndex) return@forEach
                    val key = normalize(line.substring(0, separator))
                    if (key.isEmpty()) return@forEach
                    val candidates = line.substring(separator + 1)
                        .split(' ')
                        .filter(String::isNotBlank)
                        .distinct()
                    if (candidates.isNotEmpty()) entries[key] = candidates
                }
            }
            return PinyinLexicon(entries)
        }

        internal fun normalize(value: String): String = value
            .lowercase()
            .filter { it in 'a'..'z' }
    }
}
