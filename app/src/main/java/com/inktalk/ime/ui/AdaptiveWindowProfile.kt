package com.inktalk.ime.ui

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 只依据当前窗口可用空间生成布局配置，不依赖设备型号或是否为折叠屏。
 */
data class AdaptiveWindowProfile(
    val widthDp: Int,
    val heightDp: Int,
) {
    enum class WidthClass {
        COMPACT,
        MEDIUM,
        EXPANDED,
    }

    val widthClass: WidthClass = when {
        widthDp >= EXPANDED_WIDTH_DP -> WidthClass.EXPANDED
        widthDp >= MEDIUM_WIDTH_DP -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }

    val isWide: Boolean get() = widthClass != WidthClass.COMPACT
    val isExpanded: Boolean get() = widthClass == WidthClass.EXPANDED

    /** 语音、手写和数字输入主区域不在大屏上无限拉伸。 */
    val imePrimaryContentMaxWidthDp: Int = when (widthClass) {
        WidthClass.COMPACT -> widthDp
        WidthClass.MEDIUM, WidthClass.EXPANDED -> IME_PRIMARY_MAX_WIDTH_DP
    }

    /** 快捷键允许比语音内容稍宽，但仍保持可触达的按键宽度。 */
    val imeShortcutContentMaxWidthDp: Int = when (widthClass) {
        WidthClass.COMPACT -> widthDp
        WidthClass.MEDIUM -> IME_PRIMARY_MAX_WIDTH_DP
        WidthClass.EXPANDED -> IME_SHORTCUT_MAX_WIDTH_DP
    }

    val imeControlColumnWidthDp: Int = when (widthClass) {
        WidthClass.COMPACT -> widthDp
        WidthClass.MEDIUM -> IME_CONTROL_MEDIUM_WIDTH_DP
        WidthClass.EXPANDED -> IME_CONTROL_EXPANDED_WIDTH_DP
    }

    fun contentWidthPx(availableWidthPx: Int, density: Float, maxWidthDp: Int): Int {
        val maximum = (maxWidthDp * density).roundToInt()
        return min(availableWidthPx, maximum).coerceAtLeast(1)
    }

    companion object {
        const val MEDIUM_WIDTH_DP = 600
        const val EXPANDED_WIDTH_DP = 840
        const val IME_PRIMARY_MAX_WIDTH_DP = 900
        const val IME_SHORTCUT_MAX_WIDTH_DP = 840
        const val IME_CONTROL_MEDIUM_WIDTH_DP = 340
        const val IME_CONTROL_EXPANDED_WIDTH_DP = 380
        const val APP_CONTENT_MAX_WIDTH_DP = 840

        fun fromPixels(widthPx: Int, heightPx: Int, density: Float): AdaptiveWindowProfile {
            val safeDensity = density.takeIf { it > 0f } ?: 1f
            return AdaptiveWindowProfile(
                widthDp = (widthPx / safeDensity).roundToInt().coerceAtLeast(1),
                heightDp = (heightPx / safeDensity).roundToInt().coerceAtLeast(1),
            )
        }
    }
}
