package com.inktalk.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveWindowProfileTest {
    @Test
    fun classifiesCurrentWindowWidth() {
        val compact = AdaptiveWindowProfile(widthDp = 599, heightDp = 800)
        val medium = AdaptiveWindowProfile(widthDp = 600, heightDp = 800)
        val expanded = AdaptiveWindowProfile(widthDp = 840, heightDp = 800)

        assertEquals(AdaptiveWindowProfile.WidthClass.COMPACT, compact.widthClass)
        assertEquals(AdaptiveWindowProfile.WidthClass.MEDIUM, medium.widthClass)
        assertEquals(AdaptiveWindowProfile.WidthClass.EXPANDED, expanded.widthClass)
        assertFalse(compact.isWide)
        assertTrue(medium.isWide)
        assertTrue(expanded.isExpanded)
        assertEquals(900, expanded.imePrimaryContentMaxWidthDp)
        assertEquals(340, medium.imeControlColumnWidthDp)
        assertEquals(380, expanded.imeControlColumnWidthDp)
    }

    @Test
    fun constrainsImeContentWithoutExpandingCompactWindows() {
        val profile = AdaptiveWindowProfile(widthDp = 1000, heightDp = 800)

        assertEquals(1440, profile.contentWidthPx(2000, density = 2f, maxWidthDp = 720))
        assertEquals(900, profile.contentWidthPx(900, density = 2f, maxWidthDp = 720))
    }

    @Test
    fun buildsProfileFromCurrentPixels() {
        val profile = AdaptiveWindowProfile.fromPixels(widthPx = 1768, heightPx = 2208, density = 2f)

        assertEquals(884, profile.widthDp)
        assertEquals(1104, profile.heightDp)
        assertTrue(profile.isExpanded)
    }
}
