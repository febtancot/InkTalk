package com.inktalk.ime.ui

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import com.inktalk.ime.R
import com.inktalk.ime.history.HotwordSelection
import com.inktalk.ime.history.HotwordSelectionSpan
import com.inktalk.ime.history.HotwordSelectionUnit
import kotlin.math.abs

class HotwordSelectionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    private var units: List<HotwordSelectionUnit> = emptyList()
    private val selected = linkedSetOf<Int>()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var dragActive = false
    private var dragSelecting = true
    private var lastDragIndex = -1
    private var dragBaseline: Set<Int> = emptySet()
    var onSelectionChanged: ((List<HotwordSelectionSpan>) -> Unit)? = null

    fun setText(value: String, selectedIndexes: Set<Int> = emptySet()) {
        units = HotwordSelection.units(value)
        selected.clear()
        selected += selectedIndexes.filter { it in units.indices }
        removeAllViews()
        units.forEachIndexed { index, unit -> addView(makeUnitView(index, unit)) }
        notifySelectionChanged()
    }

    fun selectedIndexes(): Set<Int> = selected.toSet()

    fun selectedSpans(): List<HotwordSelectionSpan> = HotwordSelection.spans(units, selected)

    fun clearSelection() {
        selected.clear()
        for (index in 0 until childCount) updateChild(index)
        notifySelectionChanged()
    }

    private fun makeUnitView(index: Int, unit: HotwordSelectionUnit): TextView = TextView(context).apply {
        val whitespace = unit.text.all { it.isWhitespace() }
        text = if (whitespace) " " else unit.text
        textSize = 16f
        gravity = android.view.Gravity.CENTER
        minWidth = dp(if (whitespace) 12 else 36)
        minHeight = dp(36)
        setPadding(dp(7), 0, dp(7), 0)
        isEnabled = !whitespace
        contentDescription = if (whitespace) null else context.getString(
            R.string.a11y_hotword_character,
            unit.text,
        )
        setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (!selected.add(index)) selected.remove(index)
            updateChild(index)
            notifySelectionChanged()
        }
        layoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, dp(38)).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
        updateUnitAppearance(this, index)
    }

    private fun updateChild(index: Int) {
        updateUnitAppearance(getChildAt(index) as TextView, index)
    }

    private fun updateUnitAppearance(view: TextView, index: Int) {
        val isSelected = index in selected
        view.isSelected = isSelected
        view.background = context.getDrawable(
            if (isSelected) R.drawable.bg_mode_selected else R.drawable.bg_fnkey
        )
        view.setTextColor(context.getColor(R.color.text_primary))
        view.contentDescription = view.contentDescription?.let { description ->
            if (isSelected) context.getString(R.string.a11y_hotword_character_selected, description)
            else units.getOrNull(index)?.text?.let {
                context.getString(R.string.a11y_hotword_character, it)
            }
        }
    }

    private fun notifySelectionChanged() {
        onSelectionChanged?.invoke(selectedSpans())
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downIndex = childIndexAt(event.x, event.y)
                downX = event.x
                downY = event.y
                dragActive = false
                lastDragIndex = downIndex
                dragBaseline = selected.toSet()
                dragSelecting = downIndex !in dragBaseline
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (downIndex >= 0 &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    dragActive = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    applyDragSelection(childIndexAt(event.x, event.y))
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetDrag()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragActive) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> applyDragSelection(childIndexAt(event.x, event.y))
            MotionEvent.ACTION_UP -> {
                applyDragSelection(childIndexAt(event.x, event.y))
                resetDrag()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> resetDrag()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun applyDragSelection(targetIndex: Int) {
        if (targetIndex < 0 || targetIndex == lastDragIndex && selected != dragBaseline) return
        lastDragIndex = targetIndex
        selected.clear()
        selected += dragBaseline
        val range = minOf(downIndex, targetIndex)..maxOf(downIndex, targetIndex)
        range.forEach { index ->
            if (getChildAt(index)?.isEnabled == true) {
                if (dragSelecting) selected += index else selected -= index
            }
        }
        for (index in 0 until childCount) updateChild(index)
        notifySelectionChanged()
    }

    private fun childIndexAt(x: Float, y: Float): Int {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isEnabled && x >= child.left && x < child.right && y >= child.top && y < child.bottom) {
                return index
            }
        }
        return -1
    }

    private fun resetDrag() {
        dragActive = false
        downIndex = -1
        lastDragIndex = -1
        dragBaseline = emptySet()
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = paddingTop + paddingBottom
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, totalHeight)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (lineWidth > 0 && lineWidth + childWidth > available) {
                totalHeight += lineHeight
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
        totalHeight += lineHeight
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(totalHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availableRight = r - l - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val lp = child.layoutParams as MarginLayoutParams
            val width = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val height = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > paddingLeft && x + width > availableRight) {
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }
            val left = x + lp.leftMargin
            val top = y + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            x += width
            lineHeight = maxOf(lineHeight, height)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams = MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams): Boolean = p is MarginLayoutParams

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
