package com.inktalk.ime.settings

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.inktalk.ime.R
import com.inktalk.ime.asr.HotwordCatalog
import com.inktalk.ime.history.HotwordCandidateEntry
import com.inktalk.ime.history.InputHistoryStore

/** 独立热词编辑页。 */
class HotwordSettingsActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var countView: TextView
    private lateinit var candidateTitle: TextView
    private lateinit var candidateScroll: View
    private lateinit var candidateContainer: LinearLayout
    private val historyStore by lazy { InputHistoryStore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hotword_settings)

        editor = findViewById(R.id.editHotwordList)
        countView = findViewById(R.id.textHotwordCount)
        candidateTitle = findViewById(R.id.textPendingHotwordCandidates)
        candidateScroll = findViewById(R.id.pendingHotwordCandidateScroll)
        candidateContainer = findViewById(R.id.pendingHotwordCandidates)

        findViewById<View>(R.id.btnHotwordsBack).setSystemHapticClick { finish() }
        findViewById<View>(R.id.btnRestoreHotwords).setSystemHapticClick {
            editor.setText(HotwordCatalog.defaultEditorText)
            editor.setSelection(editor.text.length)
        }
        findViewById<View>(R.id.btnSaveHotwords).setSystemHapticClick {
            val count = Prefs.putHotwords(this, editor.text.toString())
            setResult(RESULT_OK)
            Toast.makeText(
                this,
                getString(R.string.hotwords_saved_count, count),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateCount(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val restoredDraft = savedInstanceState?.getString(STATE_EDITOR_DRAFT)
        editor.setText(restoredDraft ?: HotwordCatalog.toEditorText(Prefs.hotwords(this)))
        editor.setSelection(savedInstanceState?.getInt(STATE_EDITOR_SELECTION, 0)
            ?.coerceIn(0, editor.text.length) ?: 0)
        updateCount(editor.text.toString())
        renderCandidates()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::editor.isInitialized) {
            outState.putString(STATE_EDITOR_DRAFT, editor.text.toString())
            outState.putInt(STATE_EDITOR_SELECTION, editor.selectionStart.coerceAtLeast(0))
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::candidateContainer.isInitialized) renderCandidates()
    }

    private fun renderCandidates() {
        val candidates = historyStore.pendingCandidates(50)
        val visible = candidates.isNotEmpty()
        candidateTitle.visibility = if (visible) View.VISIBLE else View.GONE
        candidateScroll.visibility = if (visible) View.VISIBLE else View.GONE
        candidateContainer.removeAllViews()
        candidates.forEach { candidate -> candidateContainer.addView(makeCandidateCard(candidate)) }
    }

    private fun makeCandidateCard(candidate: HotwordCandidateEntry): View {
        val density = resources.displayMetrics.density
        val expanded = resources.configuration.screenWidthDp >= 840
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = getDrawable(R.drawable.bg_settings_card)
            layoutParams = LinearLayout.LayoutParams(
                if (expanded) LinearLayout.LayoutParams.MATCH_PARENT else (220 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (expanded) topMargin = (8 * density).toInt()
                else marginEnd = (8 * density).toInt()
            }
            addView(TextView(this@HotwordSettingsActivity).apply {
                text = candidate.term
                textSize = 16f
                setTextColor(getColor(R.color.text_primary))
            })
            addView(TextView(this@HotwordSettingsActivity).apply {
                text = getString(R.string.hotwords_candidate_description, candidate.date, candidate.reason)
                textSize = 11f
                maxLines = 2
                setTextColor(getColor(R.color.text_hint))
            })
            addView(LinearLayout(this@HotwordSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                addView(candidateAction(getString(R.string.history_candidate_ignore)) {
                    historyStore.setCandidateStatus(candidate.id, "dismissed")
                    renderCandidates()
                })
                addView(candidateAction(getString(R.string.history_candidate_add)) {
                    Prefs.putHotwords(this@HotwordSettingsActivity, editor.text.toString())
                    Prefs.addPriorityHotwords(this@HotwordSettingsActivity, listOf(candidate.term))
                    historyStore.setCandidateStatus(candidate.id, "added")
                    editor.setText(HotwordCatalog.toEditorText(Prefs.hotwords(this@HotwordSettingsActivity)))
                    renderCandidates()
                })
            })
        }
    }

    private fun candidateAction(label: String, action: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            minWidth = (64 * density).toInt()
            minHeight = (34 * density).toInt()
            setTextColor(getColor(R.color.chip_text))
            background = getDrawable(R.drawable.bg_action_chip)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (34 * density).toInt()).apply {
                marginStart = (6 * density).toInt()
                topMargin = (6 * density).toInt()
            }
            setSystemHapticClick { action() }
        }
    }

    private fun updateCount(raw: String) {
        countView.text = getString(R.string.hotwords_count, HotwordCatalog.parse(raw).size)
    }

    private fun View.setSystemHapticClick(action: () -> Unit) {
        setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }

    companion object {
        private const val STATE_EDITOR_DRAFT = "editor_draft"
        private const val STATE_EDITOR_SELECTION = "editor_selection"
    }
}
