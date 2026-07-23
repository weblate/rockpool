package io.rebble.libpebblecommon.sailfish.calendar

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.sailfish.isReadableWithEffectiveIds
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// mkcal writes "FloatingDate" into the *TimeZone columns for date-only (all-day) values.
internal const val FLOATING_DATE = "FloatingDate"

// Recursive.RuleType values (mkcal sqliteformat).
internal const val RULE_TYPE_RRULE = 1
internal const val RULE_TYPE_EXRULE = 2

internal data class MkcalNotebook(
    val uid: String,
    val name: String,
    val color: String?,
    val flags: Long,
    val account: String?,
) {
    // SqliteFormat::Visible = 1 << 7
    val visible: Boolean get() = flags and (1L shl 7) != 0L
}

internal data class MkcalComponent(
    val componentId: Long,
    val summary: String,
    val description: String,
    val location: String?,
    val dateStart: Long,
    val dateStartLocal: Long,
    val startTimeZone: String?,
    val dateEndDue: Long,
    val dateEndDueLocal: Long,
    val endTimeZone: String?,
    val durationSecs: Long,
    val status: Int,
    val transparency: Int,
    val recurId: Long,
    val recurIdLocal: Long,
    val uid: String,
) {
    val allDay: Boolean get() = startTimeZone == FLOATING_DATE
}

internal data class MkcalRecurRow(
    val componentId: Long,
    val ruleType: Int,
    val frequency: Int,
    val until: Long,
    val count: Int,
    val interval: Int,
    val byDay: String?,
    val byDayPos: String?,
    val byMonthDay: String?,
    val byMonth: String?,
    // Any of BySecond/ByMinute/ByHour/ByYearDay/ByWeekNum/BySetPos set -> we can't expand it.
    val hasUnsupportedParts: Boolean,
)

internal data class MkcalRdate(
    val componentId: Long,
    val type: Int,
    val date: Long,
    val dateLocal: Long,
) {
    companion object {
        const val TYPE_RDATE = 1
        const val TYPE_XDATE = 2
        const val TYPE_RDATETIME = 3
        const val TYPE_XDATETIME = 4
    }
}

internal data class MkcalAlarm(
    val componentId: Long,
    val offsetSecs: Long,
    val relation: String?,
    val dateTrigger: Long,
    val enabled: Boolean,
)

internal data class MkcalAttendee(
    val componentId: Long,
    val email: String?,
    val name: String?,
    val isOrganizer: Boolean,
    val role: Int,
    val partStat: Int,
)

internal data class MkcalEventRecord(
    val component: MkcalComponent,
    val rules: List<MkcalRecurRow>,
    val rdates: List<MkcalRdate>,
    val alarms: List<MkcalAlarm>,
    val attendees: List<MkcalAttendee>,
)

/**
 * Read-only access to Sailfish's mkcal SQLite calendar database. Opens a fresh connection per
 * read and closes it promptly so we never hold locks against lipstick/the calendar app.
 * Schema per https://github.com/sailfishos/mkcal src/sqliteformat.h.
 */
internal class MkcalDatabase(
    private val dbFile: File = defaultDbFile(),
) {
    private val logger = Logger.withTag("MkcalDatabase")
    private val warned = ConcurrentHashMap.newKeySet<String>()

    // Opening also rules out a directory, so isFile would only add a redundant stat.
    fun available(): Boolean = dbFile.isReadableWithEffectiveIds()

    // mtime+size of db and -wal; changes when anything writes the calendar.
    fun changeSignature(): List<Long> =
        listOf(dbFile, File(dbFile.parentFile, dbFile.name + "-wal")).flatMap {
            try {
                listOf(it.lastModified(), it.length())
            } catch (e: SecurityException) {
                listOf(0L, 0L)
            }
        }

    fun readNotebooks(): List<MkcalNotebook> = withConnection("notebooks") { conn ->
        conn.prepare(SQL_NOTEBOOKS).use { stmt ->
            buildList {
                while (stmt.step()) {
                    val uid = stmt.textOrNull(0) ?: continue
                    add(
                        MkcalNotebook(
                            uid = uid,
                            name = stmt.textOrNull(1).orEmpty(),
                            color = stmt.textOrNull(2),
                            flags = stmt.longOrZero(3),
                            account = stmt.textOrNull(4),
                        )
                    )
                }
            }
        }
    } ?: emptyList()

    fun readEventRecords(notebookUid: String): List<MkcalEventRecord> =
        withConnection("events") { conn ->
            val components = readComponents(conn, notebookUid)
            if (components.isEmpty()) return@withConnection emptyList()
            // Aux tables read best-effort: a missing/odd table shouldn't lose the events.
            val rules = readAux(conn, "recurrence") { readRecurRows(it, notebookUid) }
            val rdates = readAux(conn, "rdates") { readRdateRows(it, notebookUid) }
            val alarms = readAux(conn, "alarms") { readAlarmRows(it, notebookUid) }
            val attendees = readAux(conn, "attendees") { readAttendeeRows(it, notebookUid) }
            components.map { c ->
                MkcalEventRecord(
                    component = c,
                    rules = rules[c.componentId].orEmpty(),
                    rdates = rdates[c.componentId].orEmpty(),
                    alarms = alarms[c.componentId].orEmpty(),
                    attendees = attendees[c.componentId].orEmpty(),
                )
            }
        } ?: emptyList()

    private fun readComponents(conn: SQLiteConnection, notebookUid: String): List<MkcalComponent> =
        conn.prepare(SQL_COMPONENTS).use { stmt ->
            stmt.bindText(1, notebookUid)
            buildList {
                while (stmt.step()) {
                    add(
                        MkcalComponent(
                            componentId = stmt.longOrZero(0),
                            summary = stmt.textOrNull(1).orEmpty(),
                            description = stmt.textOrNull(2).orEmpty(),
                            location = stmt.textOrNull(3),
                            dateStart = stmt.longOrZero(4),
                            dateStartLocal = stmt.longOrZero(5),
                            startTimeZone = stmt.textOrNull(6),
                            dateEndDue = stmt.longOrZero(7),
                            dateEndDueLocal = stmt.longOrZero(8),
                            endTimeZone = stmt.textOrNull(9),
                            durationSecs = stmt.longOrZero(10),
                            status = stmt.intOrZero(11),
                            transparency = stmt.intOrZero(12),
                            recurId = stmt.longOrZero(13),
                            recurIdLocal = stmt.longOrZero(14),
                            uid = stmt.textOrNull(15).orEmpty(),
                        )
                    )
                }
            }
        }

    private fun readRecurRows(
        conn: SQLiteConnection,
        notebookUid: String,
    ): Map<Long, List<MkcalRecurRow>> = conn.prepare(SQL_RECURSIVE).use { stmt ->
        stmt.bindText(1, notebookUid)
        buildList {
            while (stmt.step()) {
                add(
                    MkcalRecurRow(
                        componentId = stmt.longOrZero(0),
                        ruleType = stmt.intOrZero(1),
                        frequency = stmt.intOrZero(2),
                        until = stmt.longOrZero(3),
                        count = stmt.intOrZero(4),
                        interval = stmt.intOrZero(5),
                        byDay = stmt.textOrNull(6),
                        byDayPos = stmt.textOrNull(7),
                        byMonthDay = stmt.textOrNull(8),
                        byMonth = stmt.textOrNull(9),
                        hasUnsupportedParts = (10..15).any {
                            !stmt.textOrNull(it).isNullOrBlank()
                        },
                    )
                )
            }
        }
    }.groupBy { it.componentId }

    private fun readRdateRows(
        conn: SQLiteConnection,
        notebookUid: String,
    ): Map<Long, List<MkcalRdate>> = conn.prepare(SQL_RDATES).use { stmt ->
        stmt.bindText(1, notebookUid)
        buildList {
            while (stmt.step()) {
                add(
                    MkcalRdate(
                        componentId = stmt.longOrZero(0),
                        type = stmt.intOrZero(1),
                        date = stmt.longOrZero(2),
                        dateLocal = stmt.longOrZero(3),
                    )
                )
            }
        }
    }.groupBy { it.componentId }

    private fun readAlarmRows(
        conn: SQLiteConnection,
        notebookUid: String,
    ): Map<Long, List<MkcalAlarm>> = conn.prepare(SQL_ALARMS).use { stmt ->
        stmt.bindText(1, notebookUid)
        buildList {
            while (stmt.step()) {
                add(
                    MkcalAlarm(
                        componentId = stmt.longOrZero(0),
                        offsetSecs = stmt.longOrZero(1),
                        relation = stmt.textOrNull(2),
                        dateTrigger = stmt.longOrZero(3),
                        enabled = if (stmt.isNull(4)) true else stmt.getInt(4) != 0,
                    )
                )
            }
        }
    }.groupBy { it.componentId }

    private fun readAttendeeRows(
        conn: SQLiteConnection,
        notebookUid: String,
    ): Map<Long, List<MkcalAttendee>> = conn.prepare(SQL_ATTENDEES).use { stmt ->
        stmt.bindText(1, notebookUid)
        buildList {
            while (stmt.step()) {
                add(
                    MkcalAttendee(
                        componentId = stmt.longOrZero(0),
                        email = stmt.textOrNull(1),
                        name = stmt.textOrNull(2),
                        isOrganizer = stmt.intOrZero(3) != 0,
                        role = stmt.intOrZero(4),
                        partStat = stmt.intOrZero(5),
                    )
                )
            }
        }
    }.groupBy { it.componentId }

    private fun <T> readAux(
        conn: SQLiteConnection,
        key: String,
        block: (SQLiteConnection) -> Map<Long, List<T>>,
    ): Map<Long, List<T>> = try {
        block(conn)
    } catch (e: Exception) {
        warnOnce("aux-$key", e) { "mkcal '$key' read failed; continuing without" }
        emptyMap()
    }

    private fun <T> withConnection(key: String, block: (SQLiteConnection) -> T): T? {
        if (!available()) {
            warnOnce("db-missing") { "mkcal database could not be opened at ${dbFile.path}" }
            return null
        }
        return try {
            BundledSQLiteDriver().open(dbFile.absolutePath, SQLITE_OPEN_READONLY).use { conn ->
                conn.prepare("PRAGMA busy_timeout = 2000").use { it.step() }
                block(conn)
            }
        } catch (e: Exception) {
            warnOnce("read-$key", e) { "mkcal '$key' read failed" }
            null
        }
    }

    private fun warnOnce(key: String, e: Throwable? = null, msg: () -> String) {
        if (warned.add(key)) {
            if (e != null) logger.w(e) { msg() } else logger.w { msg() }
        }
    }

    private companion object {
        fun defaultDbFile(): File = File(
            System.getProperty("user.home") ?: "/home/defaultuser",
            ".local/share/system/privileged/Calendar/mkcal/db",
        )

        const val SQL_NOTEBOOKS =
            "SELECT CalendarId, Name, Color, Flags, account FROM Calendars"

        const val SQL_COMPONENTS = """
            SELECT ComponentId, Summary, Description, Location,
                   DateStart, DateStartLocal, StartTimeZone,
                   DateEndDue, DateEndDueLocal, EndDueTimeZone,
                   Duration, Status, Transparency, RecurId, RecurIdLocal, UID
            FROM Components
            WHERE Notebook = ? AND Type = 'Event' AND IFNULL(DateDeleted, 0) = 0
        """

        const val SQL_RECURSIVE = """
            SELECT r.ComponentId, r.RuleType, r.Frequency, r.Until, r.Count, r.Interval,
                   r.ByDay, r.ByDayPos, r.ByMonthDay, r.ByMonth,
                   r.BySecond, r.ByMinute, r.ByHour, r.ByYearDay, r.ByWeekNum, r.BySetPos
            FROM Recursive r JOIN Components c ON c.ComponentId = r.ComponentId
            WHERE c.Notebook = ? AND c.Type = 'Event' AND IFNULL(c.DateDeleted, 0) = 0
        """

        const val SQL_RDATES = """
            SELECT r.ComponentId, r.Type, r.Date, r.DateLocal
            FROM Rdates r JOIN Components c ON c.ComponentId = r.ComponentId
            WHERE c.Notebook = ? AND c.Type = 'Event' AND IFNULL(c.DateDeleted, 0) = 0
        """

        const val SQL_ALARMS = """
            SELECT a.ComponentId, a.Offset, a.Relation, a.DateTrigger, a.isEnabled
            FROM Alarm a JOIN Components c ON c.ComponentId = a.ComponentId
            WHERE c.Notebook = ? AND c.Type = 'Event' AND IFNULL(c.DateDeleted, 0) = 0
        """

        const val SQL_ATTENDEES = """
            SELECT a.ComponentId, a.Email, a.Name, a.IsOrganizer, a.Role, a.PartStat
            FROM Attendee a JOIN Components c ON c.ComponentId = a.ComponentId
            WHERE c.Notebook = ? AND c.Type = 'Event' AND IFNULL(c.DateDeleted, 0) = 0
        """
    }
}

private fun SQLiteStatement.textOrNull(index: Int): String? =
    if (isNull(index)) null else getText(index)

private fun SQLiteStatement.longOrZero(index: Int): Long =
    if (isNull(index)) 0L else getLong(index)

private fun SQLiteStatement.intOrZero(index: Int): Int =
    if (isNull(index)) 0 else getInt(index)
