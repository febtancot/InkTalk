package com.inktalk.ime.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InputHistoryDatesTest {
    @Test
    fun shanghaiDayUsesLocalMidnightBoundaries() {
        val range = InputHistoryDates.range(
            LocalDate.of(2026, 8, 19),
            ZoneId.of("Asia/Shanghai"),
        )

        assertTrue(range.endExclusive > range.startInclusive)
        assertEquals(24 * 60 * 60 * 1000L, range.endExclusive - range.startInclusive)
    }

    @Test
    fun daylightSavingDayUsesCalendarBoundaryRatherThanFixedDuration() {
        val range = InputHistoryDates.range(
            LocalDate.of(2026, 3, 8),
            ZoneId.of("America/New_York"),
        )

        assertEquals(23 * 60 * 60 * 1000L, range.endExclusive - range.startInclusive)
    }
}
