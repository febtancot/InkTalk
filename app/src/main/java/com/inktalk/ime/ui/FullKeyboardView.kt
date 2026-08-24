package com.inktalk.ime.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.inktalk.ime.R
import com.inktalk.ime.keyboard.FullKeyboardAction
import com.inktalk.ime.keyboard.FullKeyboardLanguage
import com.inktalk.ime.keyboard.FullKeyboardLayout
import com.inktalk.ime.keyboard.FullKeyboardPage

class FullKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onKey: ((FullKeyboardAction) -> Unit)? = null
    var onCandidate: ((String) -> Unit)? = null

    private val compositionLabel = candidateTextView().apply {
        setTextColor(context.getColor(R.color.mic_accent))
        setPadding(dp(8), 0, dp(8), 0)
    }
    private val candidateRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(compositionLabel)
    }
    private val keyRows = LinearLayout(context).apply {
        orientation = VERTICAL
    }
    private var renderedLayout: Triple<FullKeyboardLanguage, FullKeyboardPage, Boolean>? = null

    init {
        orientation = VERTICAL
        setPadding(dp(4), dp(2), dp(4), dp(3))
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                addView(candidateRow)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(34)),
        )
        addView(keyRows, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun render(
        language: FullKeyboardLanguage,
        page: FullKeyboardPage,
        uppercase: Boolean,
        composition: String,
        candidates: List<String>,
    ) {
        val layoutKey = Triple(language, page, uppercase)
        if (renderedLayout != layoutKey) {
            renderedLayout = layoutKey
            renderKeys(language, page, uppercase)
        }
        renderCandidates(composition, candidates)
    }

    private fun renderKeys(
        language: FullKeyboardLanguage,
        page: FullKeyboardPage,
        uppercase: Boolean,
    ) {
        keyRows.removeAllViews()
        FullKeyboardLayout.rows(language, page, uppercase).forEach { rowKeys ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                isBaselineAligned = false
            }
            rowKeys.forEach { key ->
                row.addView(
                    TextView(context).apply {
                        text = key.label
                        contentDescription = key.accessibilityLabel
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setTextColor(context.getColor(R.color.text_primary))
                        setBackgroundResource(R.drawable.bg_fnkey)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onKey?.invoke(key.action)
                        }
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    },
                )
            }
            keyRows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun renderCandidates(composition: String, candidates: List<String>) {
        while (candidateRow.childCount > 1) candidateRow.removeViewAt(1)
        compositionLabel.text = composition
        compositionLabel.visibility = if (composition.isEmpty()) View.GONE else View.VISIBLE
        candidates.forEach { candidate ->
            candidateRow.addView(candidateTextView().apply {
                text = candidate
                contentDescription = context.getString(R.string.full_keyboard_candidate, candidate)
                setPadding(dp(12), 0, dp(12), 0)
                setBackgroundResource(R.drawable.bg_action_chip)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onCandidate?.invoke(candidate)
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)).apply {
                marginEnd = dp(4)
            })
        }
    }

    private fun candidateTextView() = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(context.getColor(R.color.text_primary))
        isFocusable = true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
