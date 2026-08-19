package com.inktalk.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InputPanelSizingTest {
    @Test
    fun desiredHeightIsUsedWhenBelowHalfScreen() {
        assertEquals(1020, InputPanelSizing.heightPx(screenHeightPx = 2400, density = 3f))
    }

    @Test
    fun heightNeverExceedsHalfScreen() {
        assertEquals(450, InputPanelSizing.heightPx(screenHeightPx = 900, density = 2f))
    }
}
