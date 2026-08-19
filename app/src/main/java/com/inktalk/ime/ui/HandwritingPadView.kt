package com.inktalk.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.inktalk.ime.R

/**
 * 采集手指或触控笔的原始轨迹，同时在输入法面板中即时绘制。
 * 识别层使用 [snapshot] 的坐标和时间戳构建 ML Kit Ink。
 */
class HandwritingPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Point(val x: Float, val y: Float, val timestamp: Long)

    var onStrokeStarted: (() -> Unit)? = null
    var onStrokeFinished: (() -> Unit)? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_primary)
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.divider_soft)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val paths = mutableListOf<Path>()
    private val strokes = mutableListOf<MutableList<Point>>()
    private var activePath: Path? = null
    private var activeStroke: MutableList<Point>? = null

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.a11y_handwriting_pad)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val middle = height / 2f
        val padding = 12f * resources.displayMetrics.density
        canvas.drawLine(padding, middle, width - padding, middle, guidePaint)
        paths.forEach { canvas.drawPath(it, strokePaint) }
        activePath?.let { canvas.drawPath(it, strokePaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onStrokeStarted?.invoke()
                val path = Path().apply { moveTo(event.x, event.y) }
                val stroke = mutableListOf(point(event, event.x, event.y))
                activePath = path
                activeStroke = stroke
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val path = activePath ?: return false
                val stroke = activeStroke ?: return false
                for (index in 0 until event.historySize) {
                    val x = event.getHistoricalX(index)
                    val y = event.getHistoricalY(index)
                    path.lineTo(x, y)
                    stroke += Point(x, y, event.getHistoricalEventTime(index))
                }
                path.lineTo(event.x, event.y)
                stroke += point(event, event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                finishStroke(event.x, event.y, event.eventTime)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                finishStroke(event.x, event.y, event.eventTime)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    fun snapshot(): List<List<Point>> = strokes.map { it.toList() }

    fun hasInk(): Boolean = strokes.isNotEmpty() || activeStroke != null

    fun clear() {
        paths.clear()
        strokes.clear()
        activePath = null
        activeStroke = null
        invalidate()
    }

    private fun finishStroke(x: Float, y: Float, timestamp: Long) {
        val path = activePath ?: return
        val stroke = activeStroke ?: return
        path.lineTo(x, y)
        stroke += Point(x, y, timestamp)
        paths += path
        strokes += stroke
        activePath = null
        activeStroke = null
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
        onStrokeFinished?.invoke()
    }

    private fun point(event: MotionEvent, x: Float, y: Float): Point =
        Point(x, y, event.eventTime)
}
