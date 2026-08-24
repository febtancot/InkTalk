package com.inktalk.ime.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class InputSource(val wireValue: String) {
    VOICE("voice"),
    HANDWRITING("handwriting"),
    NUMERIC_KEYPAD("numeric-keypad"),
    FULL_KEYBOARD("full-keyboard"),
    INSTRUCTION("instruction"),
    AI_ACTION("ai-action");

    companion object {
        fun fromWireValue(value: String): InputSource =
            entries.firstOrNull { it.wireValue == value } ?: VOICE
    }
}

data class InputHistoryEntry(
    val id: Long,
    val createdAt: Long,
    val source: InputSource,
    val content: String,
)

data class DailyOrganization(
    val id: Long,
    val date: LocalDate,
    val createdAt: Long,
    val content: String,
    val sourceCount: Int,
    val sourceMaxEntryId: Long,
)

data class InputHistoryDayRange(val startInclusive: Long, val endExclusive: Long)

data class HotwordCandidateEntry(
    val id: Long,
    val date: LocalDate,
    val term: String,
    val sourceKind: String,
    val reason: String,
    val evidenceCount: Int,
)

object InputHistoryDates {
    fun range(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): InputHistoryDayRange =
        InputHistoryDayRange(
            startInclusive = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endExclusive = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
        )
}

/**
 * 本地输入记录。原始记录只允许新增；AI 整理结果写入独立表并保留每次整理版本。
 */
class InputHistoryStore private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE input_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                content TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX input_entries_created_at_idx ON input_entries(created_at)"
        )
        db.execSQL(
            """
            CREATE TABLE daily_organizations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                local_date TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                content TEXT NOT NULL,
                source_count INTEGER NOT NULL,
                source_max_entry_id INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX daily_organizations_date_idx
            ON daily_organizations(local_date, created_at DESC)
            """.trimIndent()
        )
        createAdaptiveHotwordTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createAdaptiveHotwordTables(db)
    }

    private fun createAdaptiveHotwordTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hotword_selections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_entry_id INTEGER NOT NULL,
                term TEXT NOT NULL,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hotword_candidates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                local_date TEXT NOT NULL,
                term TEXT NOT NULL,
                normalized_term TEXT NOT NULL,
                source_kind TEXT NOT NULL,
                source_entry_id INTEGER,
                edit_event_id INTEGER,
                evidence_count INTEGER NOT NULL,
                reason TEXT NOT NULL,
                status TEXT NOT NULL,
                source_max_entry_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(local_date, normalized_term, source_kind)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS edit_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                editor_session_id INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                verification_state TEXT NOT NULL,
                deleted_text TEXT NOT NULL,
                inserted_text TEXT NOT NULL,
                input_source TEXT NOT NULL,
                cursor_before INTEGER,
                cursor_after INTEGER,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS hotword_candidates_status_idx ON hotword_candidates(status, local_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS edit_events_created_at_idx ON edit_events(created_at)")
    }

    @Synchronized
    fun append(source: InputSource, content: String, createdAt: Long = System.currentTimeMillis()) {
        if (content.isBlank()) return
        writableDatabase.insertOrThrow(
            "input_entries",
            null,
            ContentValues().apply {
                put("created_at", createdAt)
                put("source", source.wireValue)
                put("content", content)
            },
        )
    }

    @Synchronized
    fun entriesForDate(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<InputHistoryEntry> {
        val range = InputHistoryDates.range(date, zoneId)
        val result = ArrayList<InputHistoryEntry>()
        readableDatabase.query(
            "input_entries",
            arrayOf("id", "created_at", "source", "content"),
            "created_at >= ? AND created_at < ?",
            arrayOf(range.startInclusive.toString(), range.endExclusive.toString()),
            null,
            null,
            "created_at ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += InputHistoryEntry(
                    id = cursor.getLong(0),
                    createdAt = cursor.getLong(1),
                    source = InputSource.fromWireValue(cursor.getString(2)),
                    content = cursor.getString(3),
                )
            }
        }
        return result
    }

    @Synchronized
    fun appendOrganization(
        date: LocalDate,
        content: String,
        entries: List<InputHistoryEntry>,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        require(content.isNotBlank())
        writableDatabase.insertOrThrow(
            "daily_organizations",
            null,
            ContentValues().apply {
                put("local_date", date.toString())
                put("created_at", createdAt)
                put("content", content)
                put("source_count", entries.size)
                put("source_max_entry_id", entries.maxOfOrNull { it.id } ?: 0L)
            },
        )
    }

    @Synchronized
    fun latestOrganization(date: LocalDate): DailyOrganization? {
        readableDatabase.query(
            "daily_organizations",
            arrayOf(
                "id", "local_date", "created_at", "content", "source_count",
                "source_max_entry_id",
            ),
            "local_date = ?",
            arrayOf(date.toString()),
            null,
            null,
            "created_at DESC, id DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return DailyOrganization(
                id = cursor.getLong(0),
                date = LocalDate.parse(cursor.getString(1)),
                createdAt = cursor.getLong(2),
                content = cursor.getString(3),
                sourceCount = cursor.getInt(4),
                sourceMaxEntryId = cursor.getLong(5),
            )
        }
    }

    @Synchronized
    fun appendManualSelections(entryId: Long, spans: List<HotwordSelectionSpan>) {
        if (spans.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            spans.forEach { span ->
                writableDatabase.insertOrThrow(
                    "hotword_selections", null, ContentValues().apply {
                        put("source_entry_id", entryId)
                        put("term", span.term)
                        put("start_offset", span.startUnit)
                        put("end_offset", span.endUnitExclusive)
                        put("created_at", System.currentTimeMillis())
                    }
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun upsertDailyCandidates(date: LocalDate, candidates: List<DailyHotwordCandidate>, sourceMaxEntryId: Long) {
        candidates.forEach { candidate ->
            val values = ContentValues().apply {
                put("local_date", date.toString())
                put("term", candidate.term)
                put("normalized_term", candidate.term.lowercase(Locale.ROOT))
                put("source_kind", "daily-input")
                put("evidence_count", candidate.evidenceCount)
                put("reason", candidate.reason)
                put("status", "pending")
                put("source_max_entry_id", sourceMaxEntryId)
                put("created_at", System.currentTimeMillis())
            }
            val inserted = writableDatabase.insertWithOnConflict(
                "hotword_candidates", null, ContentValues().apply {
                    putAll(values)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (inserted < 0) {
                writableDatabase.update(
                    "hotword_candidates",
                    ContentValues().apply {
                        put("term", candidate.term)
                        put("evidence_count", candidate.evidenceCount)
                        put("reason", candidate.reason)
                        put("source_max_entry_id", sourceMaxEntryId)
                    },
                    "local_date = ? AND normalized_term = ? AND source_kind = 'daily-input' AND status = 'pending'",
                    arrayOf(date.toString(), candidate.term.lowercase(Locale.ROOT)),
                )
            }
        }
    }

    @Synchronized
    fun pendingCandidatesForDate(date: LocalDate): List<HotwordCandidateEntry> =
        queryPendingCandidates("local_date = ?", arrayOf(date.toString()), 100)

    @Synchronized
    fun pendingCandidates(limit: Int = 100): List<HotwordCandidateEntry> =
        queryPendingCandidates(null, null, limit)

    private fun queryPendingCandidates(selection: String?, args: Array<String>?, limit: Int): List<HotwordCandidateEntry> {
        val where = listOfNotNull("status = 'pending'", selection).joinToString(" AND ")
        val result = ArrayList<HotwordCandidateEntry>()
        readableDatabase.query(
            "hotword_candidates",
            arrayOf("id", "local_date", "term", "source_kind", "reason", "evidence_count"),
            where,
            args,
            null,
            null,
            "created_at DESC, id DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += HotwordCandidateEntry(
                    cursor.getLong(0), LocalDate.parse(cursor.getString(1)), cursor.getString(2),
                    cursor.getString(3), cursor.getString(4), cursor.getInt(5),
                )
            }
        }
        return result
    }

    @Synchronized
    fun setCandidateStatus(id: Long, status: String) {
        require(status == "added" || status == "dismissed")
        writableDatabase.update(
            "hotword_candidates",
            ContentValues().apply { put("status", status) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    @Synchronized
    fun appendEditEvent(
        editorSessionId: Long,
        eventType: String,
        verified: Boolean,
        deletedText: String = "",
        insertedText: String = "",
        source: InputSource? = null,
        cursorBefore: Int? = null,
        cursorAfter: Int? = null,
        createdAt: Long = System.currentTimeMillis(),
    ): Long = writableDatabase.insertOrThrow(
        "edit_events", null, ContentValues().apply {
            put("editor_session_id", editorSessionId)
            put("event_type", eventType)
            put("verification_state", if (verified) "confirmed" else "unverified")
            put("deleted_text", deletedText)
            put("inserted_text", insertedText)
            put("input_source", source?.wireValue.orEmpty())
            if (cursorBefore == null) putNull("cursor_before") else put("cursor_before", cursorBefore)
            if (cursorAfter == null) putNull("cursor_after") else put("cursor_after", cursorAfter)
            put("created_at", createdAt)
        }
    )

    @Synchronized
    fun appendCorrectionCandidate(
        date: LocalDate,
        correction: CorrectionCandidate,
        editEventId: Long,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        writableDatabase.insertWithOnConflict(
            "hotword_candidates", null, ContentValues().apply {
                put("local_date", date.toString())
                put("term", correction.newTerm)
                put("normalized_term", correction.newTerm.lowercase(Locale.ROOT))
                put("source_kind", "possible-correction")
                put("edit_event_id", editEventId)
                put("evidence_count", 1)
                put("reason", "可能由“${correction.oldText}”修正为“${correction.newTerm}”")
                put("status", "pending")
                put("source_max_entry_id", 0)
                put("created_at", createdAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    companion object {
        private const val DATABASE_NAME = "input_history.db"
        private const val DATABASE_VERSION = 2

        @Volatile
        private var instance: InputHistoryStore? = null

        fun get(context: Context): InputHistoryStore =
            instance ?: synchronized(this) {
                instance ?: InputHistoryStore(context).also { instance = it }
            }
    }
}
