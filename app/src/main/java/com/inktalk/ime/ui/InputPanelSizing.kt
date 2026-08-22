package com.inktalk.ime.ui

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

object InputPanelSizing {
    const val EXTREME_VOICE_HEIGHT_DP = 128f
    const val COMPACT_VOICE_HEIGHT_DP = 240f
    const val EXPANDED_INPUT_HEIGHT_DP = 340f
    private const val EXTREME_AI_ACTIONS_FALLBACK_WIDTH_DP = 192f
    private const val EXTREME_TOOLBAR_FIXED_WIDTH_DP = 254f
    private const val EXTREME_WIDE_HINGE_GAP_DP = 8f
    private const val EXTREME_WIDE_SWAP_OFFSET_DP = 34f

    fun desiredHeightDp(
        expandedInput: Boolean,
        extremeHeightMode: Boolean = false,
        shortcutPageVisible: Boolean = false,
    ): Float = when {
        expandedInput -> EXPANDED_INPUT_HEIGHT_DP
        extremeHeightMode && !shortcutPageVisible -> EXTREME_VOICE_HEIGHT_DP
        else -> COMPACT_VOICE_HEIGHT_DP
    }

    fun canPlaceExtremeModeButtonsInToolbar(
        availableWidthPx: Int,
        density: Float,
        aiActionsWidthPx: Int = (EXTREME_AI_ACTIONS_FALLBACK_WIDTH_DP * density).roundToInt(),
    ): Boolean {
        if (density <= 0f || availableWidthPx <= 0 || aiActionsWidthPx <= 0) return false
        val fixedToolbarWidthPx = (EXTREME_TOOLBAR_FIXED_WIDTH_DP * density).roundToInt()
        return availableWidthPx >= aiActionsWidthPx + fixedToolbarWidthPx
    }

    fun extremeWideToolbarWidthPx(availableWidthPx: Int, density: Float): Int {
        val hingeGapPx = (EXTREME_WIDE_HINGE_GAP_DP * density).roundToInt()
        return (availableWidthPx / 2 - hingeGapPx).coerceAtLeast(1)
    }

    fun extremeWideControlTranslationPx(
        availableWidthPx: Int,
        controlsOnRight: Boolean,
    ): Float = availableWidthPx / 4f * if (controlsOnRight) 1f else -1f

    fun extremeWideSwapTranslationPx(
        density: Float,
        controlsOnRight: Boolean,
    ): Float = EXTREME_WIDE_SWAP_OFFSET_DP * density * if (controlsOnRight) 1f else -1f

    fun usesExtremeWideSingleHandLayout(
        isWideWindow: Boolean,
        isLandscape: Boolean,
    ): Boolean = isWideWindow && isLandscape

    fun heightPx(
        screenHeightPx: Int,
        density: Float,
        desiredHeightDp: Float = EXPANDED_INPUT_HEIGHT_DP,
        maximumScreenFraction: Float = 0.5f,
    ): Int {
        val desired = (desiredHeightDp * density).roundToInt()
        val maximum = floor(screenHeightPx * maximumScreenFraction).toInt()
        return min(desired, maximum).coerceAtLeast(1)
    }
}
