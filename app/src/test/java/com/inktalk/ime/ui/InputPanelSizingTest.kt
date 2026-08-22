package com.inktalk.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InputPanelSizingTest {
    @Test
    fun voiceModeUsesCompactHeightUntilExpandedInputIsActive() {
        assertEquals(240f, InputPanelSizing.desiredHeightDp(expandedInput = false))
        assertEquals(340f, InputPanelSizing.desiredHeightDp(expandedInput = true))
    }

    @Test
    fun extremeModeOnlyChangesTheVoicePageHeight() {
        assertEquals(
            128f,
            InputPanelSizing.desiredHeightDp(
                expandedInput = false,
                extremeHeightMode = true,
            ),
        )
        assertEquals(
            240f,
            InputPanelSizing.desiredHeightDp(
                expandedInput = false,
                extremeHeightMode = true,
                shortcutPageVisible = true,
            ),
        )
    }

    @Test
    fun expandedInputWinsOverExtremeMode() {
        assertEquals(
            340f,
            InputPanelSizing.desiredHeightDp(
                expandedInput = true,
                extremeHeightMode = true,
            ),
        )
    }

    @Test
    fun narrowExtremeModeKeepsModeButtonsAtBottomLeft() {
        assertEquals(
            false,
            InputPanelSizing.canPlaceExtremeModeButtonsInToolbar(
                availableWidthPx = 720,
                density = 2f,
                aiActionsWidthPx = 384,
            ),
        )
        assertEquals(
            false,
            InputPanelSizing.canPlaceExtremeModeButtonsInToolbar(
                availableWidthPx = 891,
                density = 2f,
                aiActionsWidthPx = 384,
            ),
        )
        assertEquals(
            true,
            InputPanelSizing.canPlaceExtremeModeButtonsInToolbar(
                availableWidthPx = 892,
                density = 2f,
                aiActionsWidthPx = 384,
            ),
        )
    }

    @Test
    fun wideExtremeModeMirrorsTheOneHandedControlZone() {
        assertEquals(584, InputPanelSizing.extremeWideToolbarWidthPx(1200, density = 2f))
        assertEquals(
            300f,
            InputPanelSizing.extremeWideControlTranslationPx(1200, controlsOnRight = true),
        )
        assertEquals(
            -300f,
            InputPanelSizing.extremeWideControlTranslationPx(1200, controlsOnRight = false),
        )
        assertEquals(
            68f,
            InputPanelSizing.extremeWideSwapTranslationPx(2f, controlsOnRight = true),
        )
        assertEquals(
            -68f,
            InputPanelSizing.extremeWideSwapTranslationPx(2f, controlsOnRight = false),
        )
    }

    @Test
    fun portraitInnerScreenKeepsTheOuterScreenExtremeLayout() {
        assertEquals(
            false,
            InputPanelSizing.usesExtremeWideSingleHandLayout(
                isWideWindow = true,
                isLandscape = false,
            ),
        )
        assertEquals(
            true,
            InputPanelSizing.usesExtremeWideSingleHandLayout(
                isWideWindow = true,
                isLandscape = true,
            ),
        )
    }

    @Test
    fun compactVoiceHeightStillHonorsHalfScreenLimit() {
        assertEquals(
            720,
            InputPanelSizing.heightPx(
                screenHeightPx = 2400,
                density = 3f,
                desiredHeightDp = InputPanelSizing.desiredHeightDp(expandedInput = false),
            ),
        )
    }

    @Test
    fun desiredHeightIsUsedWhenBelowHalfScreen() {
        assertEquals(1020, InputPanelSizing.heightPx(screenHeightPx = 2400, density = 3f))
    }

    @Test
    fun heightNeverExceedsHalfScreen() {
        assertEquals(450, InputPanelSizing.heightPx(screenHeightPx = 900, density = 2f))
    }
}
