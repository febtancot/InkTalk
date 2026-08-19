package com.inktalk.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.inktalk.ime.R

/** 简单的音量波形：最近若干个采样点绘制成竖条。 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val levels = ArrayDeque<Int>()
    private val maxBars = 40
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.wave_bar)
        strokeCap = Paint.Cap.ROUND
    }

    fun push(level: Int) {
        levels.addLast(level.coerceIn(0, 100))
        while (levels.size > maxBars) levels.removeFirst()
        invalidate()
    }

    fun clear() {
        levels.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (levels.isEmpty()) return
        val slot = width.toFloat() / maxBars
        val barWidth = slot * 0.5f
        paint.strokeWidth = barWidth
        val midY = height / 2f
        val maxHalf = height / 2f * 0.9f
        levels.forEachIndexed { i, lv ->
            val x = width - (levels.size - i) * slot + slot / 2f
            val half = maxOf(2f, maxHalf * lv / 100f)
            canvas.drawLine(x, midY - half, x, midY + half, paint)
        }
    }
}
