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
import com.inktalk.ime.settings.Prefs
import com.inktalk.ime.settings.SettingsActivity
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

    private var selectedDate: LocalDate = LocalDate.now()
    private var visibleEntries: List<InputHistoryEntry> = emptyList()
    private var loadGeneration = 0L
    private var organizationCall: Call? = null

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

        findViewById<View>(R.id.btnHistorySettings).setHapticClick {
            startActivity(Intent(this, SettingsActivity::class.java))
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
        io.shutdownNow()
        super.onDestroy()
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
            main.post {
                if (generation != loadGeneration || date != selectedDate || isFinishing) return@post
                visibleEntries = entries
                render(entries, organization)
            }
        }
    }

    private fun render(entries: List<InputHistoryEntry>, organization: DailyOrganization?) {
        rawTitle.text = getString(R.string.history_raw_title_count, entries.size)
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        entriesContainer.removeAllViews()
        entries.forEach { entriesContainer.addView(makeEntryView(it)) }

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
        return card
    }

    private fun sourceLabel(source: InputSource): String = getString(when (source) {
        InputSource.VOICE -> R.string.history_source_voice
        InputSource.HANDWRITING -> R.string.history_source_handwriting
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
                        organizeButton.isEnabled = true
                        showStatus(getString(R.string.history_organization_saved), error = false)
                        loadSelectedDate()
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
}
