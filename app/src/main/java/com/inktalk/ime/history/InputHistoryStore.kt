package com.inktalk.ime.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId

enum class InputSource(val wireValue: String) {
    VOICE("voice"),
    HANDWRITING("handwriting"),
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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

    companion object {
        private const val DATABASE_NAME = "input_history.db"
        private const val DATABASE_VERSION = 1

        @Volatile
        private var instance: InputHistoryStore? = null

        fun get(context: Context): InputHistoryStore =
            instance ?: synchronized(this) {
                instance ?: InputHistoryStore(context).also { instance = it }
            }
    }
}
