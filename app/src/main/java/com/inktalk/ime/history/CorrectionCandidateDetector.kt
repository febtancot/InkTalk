package com.inktalk.ime.history

data class CorrectionCandidate(val oldText: String, val newTerm: String)

object CorrectionCandidateDetector {
    fun detect(deletedText: String, insertedText: String): CorrectionCandidate? {
        val old = deletedText.trim()
        val new = insertedText.trim()
        if (old.isEmpty() || new.isEmpty() || old.equals(new, ignoreCase = true)) return null
        val oldUnits = HotwordSelection.units(old)
        val newUnits = HotwordSelection.units(new)
        if (oldUnits.size !in 1..32 || newUnits.size !in 1..32) return null
        if (new.contains('\n') || new.none { it.isLetterOrDigit() }) return null
        return CorrectionCandidate(old, new)
    }
}
