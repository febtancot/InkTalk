package com.inktalk.ime.history

import java.text.BreakIterator
import java.util.Locale

data class HotwordSelectionUnit(val text: String, val start: Int, val end: Int)

data class HotwordSelectionSpan(
    val term: String,
    val startUnit: Int,
    val endUnitExclusive: Int,
)

object HotwordSelection {
    fun units(text: String): List<HotwordSelectionUnit> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val result = ArrayList<HotwordSelectionUnit>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            result += HotwordSelectionUnit(text.substring(start, end), start, end)
            start = end
            end = iterator.next()
        }
        return result
    }

    fun spans(units: List<HotwordSelectionUnit>, selectedIndexes: Set<Int>): List<HotwordSelectionSpan> {
        val selected = selectedIndexes.filter { it in units.indices }.sorted()
        if (selected.isEmpty()) return emptyList()
        val result = ArrayList<HotwordSelectionSpan>()
        var rangeStart = selected.first()
        var previous = rangeStart
        fun flush(endExclusive: Int) {
            val term = units.subList(rangeStart, endExclusive).joinToString("") { it.text }.trim()
            if (term.isNotEmpty() && term.any { it.isLetterOrDigit() }) {
                result += HotwordSelectionSpan(term, rangeStart, endExclusive)
            }
        }
        selected.drop(1).forEach { index ->
            if (index != previous + 1) {
                flush(previous + 1)
                rangeStart = index
            }
            previous = index
        }
        flush(previous + 1)
        return result
    }
}
