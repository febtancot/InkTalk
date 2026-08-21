package com.inktalk.ime.history

import org.json.JSONArray
import java.util.Locale

data class DailyHotwordCandidate(
    val term: String,
    val reason: String,
    val evidenceCount: Int,
)

object DailyHotwordCandidateParser {
    fun parse(raw: String): List<DailyHotwordCandidate> {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val array = try { JSONArray(cleaned) } catch (_: Exception) { return emptyList() }
        val seen = HashSet<String>()
        val result = ArrayList<DailyHotwordCandidate>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val term = item.optString("term").trim()
            if (!isValidTerm(term)) continue
            val key = term.lowercase(Locale.ROOT)
            if (!seen.add(key)) continue
            result += DailyHotwordCandidate(
                term = term,
                reason = item.optString("reason").trim().take(80),
                evidenceCount = item.optInt("evidence_count", 1).coerceIn(1, 999),
            )
        }
        return result.take(20)
    }

    private fun isValidTerm(term: String): Boolean =
        term.length in 1..40 && term.none { it == '\n' || it == '\r' } &&
            term.any { it.isLetterOrDigit() }

}
