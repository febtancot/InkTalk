package com.inktalk.ime.ui

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

object InputPanelSizing {
    fun heightPx(
        screenHeightPx: Int,
        density: Float,
        desiredHeightDp: Float = 340f,
        maximumScreenFraction: Float = 0.5f,
    ): Int {
        val desired = (desiredHeightDp * density).roundToInt()
        val maximum = floor(screenHeightPx * maximumScreenFraction).toInt()
        return min(desired, maximum).coerceAtLeast(1)
    }
}
