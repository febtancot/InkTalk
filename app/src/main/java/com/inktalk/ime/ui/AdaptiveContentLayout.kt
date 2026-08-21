package com.inktalk.ime.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import kotlin.math.min

/**
 * 手机端保持全宽；窗口变宽后，将表单和长文本限制在可读宽度内。
 */
class AdaptiveContentLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = View.MeasureSpec.getSize(widthMeasureSpec)
        val maxWidth = (AdaptiveWindowProfile.APP_CONTENT_MAX_WIDTH_DP * resources.displayMetrics.density)
            .toInt()
        val constrainedSize = min(widthSize, maxWidth)
        val constrainedSpec = when (widthMode) {
            View.MeasureSpec.EXACTLY -> View.MeasureSpec.makeMeasureSpec(constrainedSize, View.MeasureSpec.EXACTLY)
            View.MeasureSpec.AT_MOST -> View.MeasureSpec.makeMeasureSpec(constrainedSize, View.MeasureSpec.AT_MOST)
            else -> widthMeasureSpec
        }
        super.onMeasure(constrainedSpec, heightMeasureSpec)
    }
}
