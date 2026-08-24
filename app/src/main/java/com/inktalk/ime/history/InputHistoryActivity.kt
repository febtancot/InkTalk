package com.inktalk.ime.history

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.inktalk.ime.R
import com.inktalk.ime.ai.AiProcessor
import com.inktalk.ime.ai.DailyOrganizationPromptBuilder
import com.inktalk.ime.ai.DailyHotwordCandidatePromptBuilder
import com.inktalk.ime.settings.Prefs
import com.inktalk.ime.settings.HotwordSettingsActivity
import com.inktalk.ime.settings.SettingsActivity
import com.inktalk.ime.ui.HotwordSelectionLayout
import okhttp3.Call
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class InputHistoryActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val store by lazy { InputHistoryStore.get(this) }
    private val dateFormatter = DateTimeFormatter.ofPattern("M 月 d 日 EEEE", Locale.SIMPLIFIED_CHINESE)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)

    private lateinit var textDate: TextView
    private lateinit var textStatus: TextView
    private lateinit var rawTitle: TextView
    private lateinit var emptyView: TextView
    private lateinit var entriesContainer: LinearLayout
    private lateinit var organizedCard: View
    private lateinit var organizationMeta: TextView
    private lateinit var organizationContent: TextView
    private lateinit var organizeButton: Button
    private lateinit var candidateTitle: View
    private lateinit var candidateDescription: View
    private lateinit var candidateContainer: LinearLayout

    private var selectedDate: LocalDate = LocalDate.now()
    private var visibleEntries: List<InputHistoryEntry> = emptyList()
    private var loadGeneration = 0L
    private var organizationCall: Call? = null
    private var candidateCall: Call? = null
    private var selectingEntryId: Long? = null
    private val selectedUnitIndexes = linkedSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_history)
        textDate = findViewById(R.id.textHistoryDate)
        textStatus = findViewById(R.id.textHistoryStatus)
        rawTitle = findViewById(R.id.textRawHistoryTitle)
        emptyView = findViewById(R.id.textHistoryEmpty)
        entriesContainer = findViewById(R.id.historyEntries)
        organizedCard = findViewById(R.id.organizedCard)
        organizationMeta = findViewById(R.id.textOrganizationMeta)
        organizationContent = findViewById(R.id.textOrganizationContent)
        organizeButton = findViewById(R.id.btnOrganizeDay)
        candidateTitle = findViewById(R.id.textCandidateSectionTitle)
        candidateDescription = findViewById(R.id.textCandidateSectionDescription)
        candidateContainer = findViewById(R.id.historyCandidateContainer)

        selectedDate = savedInstanceState?.getString(STATE_SELECTED_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        selectingEntryId = savedInstanceState?.getLong(STATE_SELECTING_ENTRY_ID, -1L)
            ?.takeIf { it >= 0 }
        savedInstanceState?.getIntArray(STATE_SELECTED_INDEXES)?.let { selectedUnitIndexes += it.toList() }

        findViewById<View>(R.id.btnHistorySettings).setHapticClick {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnHistoryHotwords)?.setHapticClick {
            startActivity(Intent(this, HotwordSettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnHistoryToday)?.setHapticClick {
            selectedDate = LocalDate.now()
            loadSelectedDate()
        }
        findViewById<View>(R.id.btnPreviousDate).setHapticClick {
            selectedDate = selectedDate.minusDays(1)
            loadSelectedDate()
        }
        findViewById<View>(R.id.btnNextDate).setHapticClick {
            selectedDate = selectedDate.plusDays(1)
            loadSelectedDate()
        }
        findViewById<View>(R.id.btnToday).setHapticClick {
            selectedDate = LocalDate.now()
            loadSelectedDate()
        }
        textDate.setHapticClick { showDatePicker() }
        organizeButton.setHapticClick { organizeSelectedDate() }
        loadSelectedDate()
    }

    override fun onResume() {
        super.onResume()
        if (::textDate.isInitialized) loadSelectedDate()
    }

    override fun onDestroy() {
        loadGeneration += 1
        organizationCall?.cancel()
        candidateCall?.cancel()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_DATE, selectedDate.toString())
        selectingEntryId?.let { outState.putLong(STATE_SELECTING_ENTRY_ID, it) }
        outState.putIntArray(STATE_SELECTED_INDEXES, selectedUnitIndexes.toIntArray())
        super.onSaveInstanceState(outState)
    }

    private fun showDatePicker() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                loadSelectedDate()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth,
        ).show()
    }

    private fun loadSelectedDate() {
        val date = selectedDate
        val generation = ++loadGeneration
        textDate.text = date.format(dateFormatter)
        io.execute {
            val entries = store.entriesForDate(date)
            val organization = store.latestOrganization(date)
            val candidates = store.pendingCandidatesForDate(date)
            main.post {
                if (generation != loadGeneration || date != selectedDate || isFinishing) return@post
                visibleEntries = entries
                render(entries, organization, candidates)
            }
        }
    }

    private fun render(
        entries: List<InputHistoryEntry>,
        organization: DailyOrganization?,
        candidates: List<HotwordCandidateEntry>,
    ) {
        rawTitle.text = getString(R.string.history_raw_title_count, entries.size)
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        entriesContainer.removeAllViews()
        entries.forEach { entriesContainer.addView(makeEntryView(it)) }
        renderCandidates(candidates)

        organizedCard.visibility = if (organization == null) View.GONE else View.VISIBLE
        if (organization != null) {
            organizationContent.text = organization.content
            val pendingCount = entries.count { it.id > organization.sourceMaxEntryId }
            val organizedTime = Instant.ofEpochMilli(organization.createdAt)
                .atZone(ZoneId.systemDefault())
                .format(timeFormatter)
            organizationMeta.text = if (pendingCount > 0) {
                getString(
                    R.string.history_organization_meta_stale,
                    organizedTime,
                    organization.sourceCount,
                    pendingCount,
                )
            } else {
                getString(
                    R.string.history_organization_meta,
                    organizedTime,
                    organization.sourceCount,
                )
            }
        }
    }

    private fun makeEntryView(entry: InputHistoryEntry): View {
        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = getDrawable(R.drawable.bg_settings_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * density).toInt() }
        }
        val time = Instant.ofEpochMilli(entry.createdAt)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
        card.addView(TextView(this).apply {
            text = getString(R.string.history_entry_meta, time, sourceLabel(entry.source))
            textSize = 12f
            setTextColor(getColor(R.color.text_hint))
        })
        if (selectingEntryId == entry.id) {
            val preview = TextView(this).apply {
                textSize = 13f
                setTextColor(getColor(R.color.text_hint))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (8 * density).toInt() }
            }
            val addButton = makeSmallAction("") { }
            val selector = HotwordSelectionLayout(this).apply {
                setPadding(0, (6 * density).toInt(), 0, (4 * density).toInt())
                onSelectionChanged = { spans ->
                    selectedUnitIndexes.clear()
                    selectedUnitIndexes += selectedIndexes()
                    preview.text = if (spans.isEmpty()) {
                        getString(R.string.history_hotword_selection_empty)
                    } else {
                        getString(
                            R.string.history_selected_hotword_preview,
                            spans.joinToString("、") { it.term },
                        )
                    }
                    addButton.text = getString(R.string.history_add_selected_hotwords, spans.size)
                    addButton.isEnabled = spans.isNotEmpty()
                    addButton.alpha = if (spans.isNotEmpty()) 1f else 0.45f
                }
                setText(entry.content, selectedUnitIndexes)
            }
            addButton.setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                addManualHotwords(entry, selector.selectedSpans())
            }
            card.addView(selector)
            card.addView(preview)
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                addView(makeSmallAction(getString(R.string.history_cancel_hotword_selection)) {
                    selectingEntryId = null
                    selectedUnitIndexes.clear()
                    loadSelectedDate()
                })
                addView(makeSmallAction(getString(R.string.history_clear_hotword_selection)) {
                    selector.clearSelection()
                })
                addView(addButton)
            })
        } else {
            card.addView(TextView(this).apply {
                text = entry.content
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                setLineSpacing(0f, 1.2f)
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (6 * density).toInt() }
            })
            card.addView(makeSmallAction(getString(R.string.history_select_hotwords)) {
                selectingEntryId = entry.id
                selectedUnitIndexes.clear()
                loadSelectedDate()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * density).toInt(),
                ).apply {
                    gravity = android.view.Gravity.END
                    topMargin = (8 * density).toInt()
                }
            })
        }
        return card
    }

    private fun makeSmallAction(label: String, action: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            minWidth = (64 * density).toInt()
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            setTextColor(getColor(R.color.chip_text))
            background = getDrawable(R.drawable.bg_action_chip)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt(),
            ).apply { marginStart = (6 * density).toInt() }
            setHapticClick(action)
        }
    }

    private fun addManualHotwords(entry: InputHistoryEntry, spans: List<HotwordSelectionSpan>) {
        if (spans.isEmpty()) return
        val result = Prefs.addPriorityHotwords(this, spans.map { it.term })
        io.execute {
            store.appendManualSelections(entry.id, spans)
            main.post {
                if (isFinishing || isDestroyed) return@post
                selectingEntryId = null
                selectedUnitIndexes.clear()
                showStatus(
                    if (result.added.isEmpty()) getString(R.string.history_hotwords_all_duplicate)
                    else getString(R.string.history_hotwords_added, result.added.size),
                    error = false,
                )
                loadSelectedDate()
            }
        }
    }

    private fun renderCandidates(candidates: List<HotwordCandidateEntry>) {
        val visible = candidates.isNotEmpty()
        candidateTitle.visibility = if (visible) View.VISIBLE else View.GONE
        candidateDescription.visibility = if (visible) View.VISIBLE else View.GONE
        candidateContainer.removeAllViews()
        candidates.forEach { candidate -> candidateContainer.addView(makeCandidateView(candidate)) }
    }

    private fun makeCandidateView(candidate: HotwordCandidateEntry): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = getDrawable(R.drawable.bg_settings_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * density).toInt() }
            addView(TextView(this@InputHistoryActivity).apply {
                text = candidate.term
                textSize = 17f
                setTextColor(getColor(R.color.text_primary))
            })
            addView(TextView(this@InputHistoryActivity).apply {
                text = getString(
                    if (candidate.sourceKind == "possible-correction") {
                        R.string.history_candidate_meta_correction
                    } else {
                        R.string.history_candidate_meta_daily
                    },
                    candidate.reason,
                )
                textSize = 12f
                setTextColor(getColor(R.color.text_hint))
            })
            addView(LinearLayout(this@InputHistoryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                addView(makeSmallAction(getString(R.string.history_candidate_ignore)) {
                    updateCandidate(candidate, add = false)
                })
                addView(makeSmallAction(getString(R.string.history_candidate_add)) {
                    updateCandidate(candidate, add = true)
                })
            })
        }
    }

    private fun updateCandidate(candidate: HotwordCandidateEntry, add: Boolean) {
        if (add) Prefs.addPriorityHotwords(this, listOf(candidate.term))
        io.execute {
            store.setCandidateStatus(candidate.id, if (add) "added" else "dismissed")
            main.post {
                if (isFinishing || isDestroyed) return@post
                showStatus(
                    getString(if (add) R.string.history_candidate_added else R.string.history_candidate_ignored),
                    error = false,
                )
                loadSelectedDate()
            }
        }
    }

    private fun sourceLabel(source: InputSource): String = getString(when (source) {
        InputSource.VOICE -> R.string.history_source_voice
        InputSource.HANDWRITING -> R.string.history_source_handwriting
        InputSource.NUMERIC_KEYPAD -> R.string.history_source_numeric_keypad
        InputSource.FULL_KEYBOARD -> R.string.history_source_full_keyboard
        InputSource.INSTRUCTION -> R.string.history_source_instruction
        InputSource.AI_ACTION -> R.string.history_source_ai
    })

    private fun organizeSelectedDate() {
        val date = selectedDate
        val entries = visibleEntries
        if (entries.isEmpty()) {
            showStatus(getString(R.string.history_empty_cannot_organize), error = true)
            return
        }
        if (!Prefs.hasAiConfig(this)) {
            showStatus(getString(R.string.ai_not_configured), error = true)
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val prompt = DailyOrganizationPromptBuilder.build(date, entries)
        organizeButton.isEnabled = false
        showStatus(getString(R.string.history_organizing), error = false)
        organizationCall?.cancel()
        organizationCall = AiProcessor.processInstruction(this, prompt, object : AiProcessor.Callback {
            override fun onResult(text: String) {
                organizationCall = null
                if (isFinishing || isDestroyed) return
                val result = text.trim()
                if (result.isEmpty()) {
                    organizeButton.isEnabled = true
                    showStatus(getString(R.string.history_organization_empty), error = true)
                    return
                }
                io.execute {
                    store.appendOrganization(date, result, entries)
                    main.post {
                        if (isFinishing || isDestroyed) return@post
                        loadSelectedDate()
                        extractDailyHotwordCandidates(date, entries)
                    }
                }
            }

            override fun onError(message: String) {
                organizationCall = null
                if (isFinishing || isDestroyed) return
                organizeButton.isEnabled = true
                showStatus(message, error = true)
            }
        })
    }

    private fun extractDailyHotwordCandidates(date: LocalDate, entries: List<InputHistoryEntry>) {
        val prompt = DailyHotwordCandidatePromptBuilder.build(date, entries)
        candidateCall?.cancel()
        candidateCall = AiProcessor.processInstruction(this, prompt, object : AiProcessor.Callback {
            override fun onResult(text: String) {
                candidateCall = null
                if (isFinishing || isDestroyed) return
                val existingKeys = com.inktalk.ime.asr.HotwordCatalog.parse(Prefs.hotwords(this@InputHistoryActivity))
                    .mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
                val candidates = DailyHotwordCandidateParser.parse(text)
                    .filter { it.term.lowercase(Locale.ROOT) !in existingKeys }
                io.execute {
                    store.upsertDailyCandidates(
                        date,
                        candidates,
                        entries.maxOfOrNull { it.id } ?: 0L,
                    )
                    main.post {
                        if (isFinishing || isDestroyed) return@post
                        organizeButton.isEnabled = true
                        showStatus(
                            if (candidates.isEmpty()) getString(R.string.history_organization_saved)
                            else getString(R.string.history_organization_saved_with_candidates, candidates.size),
                            error = false,
                        )
                        loadSelectedDate()
                    }
                }
            }

            override fun onError(message: String) {
                candidateCall = null
                if (isFinishing || isDestroyed) return
                organizeButton.isEnabled = true
                showStatus(getString(R.string.history_organization_saved_candidate_failed), error = false)
                loadSelectedDate()
            }
        })
    }

    private fun showStatus(message: String, error: Boolean) {
        textStatus.visibility = View.VISIBLE
        textStatus.text = message
        textStatus.setTextColor(getColor(if (error) R.color.error_red else R.color.text_hint))
    }

    private fun View.setHapticClick(action: () -> Unit) {
        setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }

    companion object {
        private const val STATE_SELECTED_DATE = "selected_date"
        private const val STATE_SELECTING_ENTRY_ID = "selecting_entry_id"
        private const val STATE_SELECTED_INDEXES = "selected_unit_indexes"
    }
}
