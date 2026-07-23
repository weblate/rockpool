package io.rebble.libpebblecommon.sailfish.contacts

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.contacts.SystemContact
import io.rebble.libpebblecommon.sailfish.isReadableWithEffectiveIds
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only access to Sailfish's qtcontacts-sqlite database. The db is opened fresh per query
 * batch and closed straight after so the daemon never holds locks against contactsd.
 *
 * Two schema generations are supported: SFOS 4+ keeps names in DisplayLabels/Names tables and
 * aggregate contacts in collection 1; older schemas kept displayLabel/firstName/lastName on
 * Contacts itself with syncTarget = 'aggregate'.
 */
internal object QtContactsDb {
    private val logger = Logger.withTag("QtContactsDb")
    private val warnedUnavailable = AtomicBoolean(false)

    // Qt's MatchPhoneNumber semantics: compare the last digits of the normalized numbers.
    private const val SUFFIX_DIGITS = 8

    private val dbFile = File(
        System.getProperty("user.home") ?: ".",
        ".local/share/system/privileged/Contacts/qtcontacts-sqlite/contacts.db",
    )

    fun isAvailable(): Boolean = dbFile.isReadableWithEffectiveIds()

    /** Newest mtime of the db + WAL (writes usually only touch the -wal file). */
    fun lastModified(): Long = try {
        maxOf(dbFile.lastModified(), File(dbFile.path + "-wal").lastModified())
    } catch (e: SecurityException) {
        0L
    }

    fun getContacts(): List<SystemContact> = withDb { conn ->
        val sql = if (hasDetailTables(conn)) CONTACTS_QUERY else CONTACTS_QUERY_LEGACY
        conn.prepare(sql).use { stmt ->
            buildList {
                while (stmt.step()) {
                    val name = rowName(stmt, 1, 2, 3) ?: continue
                    add(SystemContact(name = name, key = stmt.getLong(0).toString()))
                }
            }
        }
    } ?: emptyList()

    fun lookupNameByNumber(number: String): String? {
        val suffix = number.filter { it.isDigit() }.takeLast(SUFFIX_DIGITS)
        if (suffix.isEmpty()) return null
        return withDb { conn ->
            val sql = if (hasDetailTables(conn)) NUMBERS_QUERY else NUMBERS_QUERY_LEGACY
            conn.prepare(sql).use { stmt ->
                while (stmt.step()) {
                    val stored = (textOrNull(stmt, 1) ?: textOrNull(stmt, 0)).orEmpty()
                        .filter { it.isDigit() }
                        .takeLast(SUFFIX_DIGITS)
                    if (stored.isNotEmpty() && stored == suffix) {
                        return@withDb rowName(stmt, 2, 3, 4)
                    }
                }
                null
            }
        }
    }

    private fun <T> withDb(block: (SQLiteConnection) -> T): T? {
        if (!isAvailable()) {
            if (warnedUnavailable.compareAndSet(false, true)) {
                logger.w { "contacts db could not be opened at $dbFile" }
            }
            return null
        }
        return try {
            BundledSQLiteDriver().open(dbFile.absolutePath, SQLITE_OPEN_READONLY).use { conn ->
                // Don't fail instantly if contactsd is mid-write.
                conn.prepare("PRAGMA busy_timeout = 500").use { it.step() }
                block(conn)
            }
        } catch (e: Throwable) {
            if (warnedUnavailable.compareAndSet(false, true)) {
                logger.w("contacts db query failed", e)
            } else {
                logger.d { "contacts db query failed: ${e.message}" }
            }
            null
        }
    }

    private fun hasDetailTables(conn: SQLiteConnection): Boolean =
        conn.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'DisplayLabels'")
            .use { it.step() }

    private fun rowName(stmt: SQLiteStatement, labelCol: Int, firstCol: Int, lastCol: Int): String? {
        textOrNull(stmt, labelCol)?.takeIf { it.isNotBlank() }?.let { return it }
        val first = textOrNull(stmt, firstCol).orEmpty()
        val last = textOrNull(stmt, lastCol).orEmpty()
        return "$first $last".trim().ifEmpty { null }
    }

    private fun textOrNull(stmt: SQLiteStatement, index: Int): String? =
        if (stmt.isNull(index)) null else stmt.getText(index)

    // collectionId 1 = built-in aggregate addressbook; changeFlags bit 4 = deleted.
    private val CONTACTS_QUERY = """
        SELECT c.contactId, dl.displayLabel, n.firstName, n.lastName
        FROM Contacts c
        LEFT JOIN DisplayLabels dl ON dl.contactId = c.contactId
        LEFT JOIN Names n ON n.contactId = c.contactId
        WHERE c.collectionId = 1
          AND c.deleted IS NULL
          AND c.isDeactivated = 0
          AND (c.changeFlags & 4) = 0
    """.trimIndent()

    private val CONTACTS_QUERY_LEGACY = """
        SELECT contactId, displayLabel, firstName, lastName
        FROM Contacts
        WHERE syncTarget = 'aggregate' AND isDeactivated = 0
    """.trimIndent()

    private val NUMBERS_QUERY = """
        SELECT p.phoneNumber, p.normalizedNumber, dl.displayLabel, n.firstName, n.lastName
        FROM PhoneNumbers p
        JOIN Contacts c ON c.contactId = p.contactId
        LEFT JOIN DisplayLabels dl ON dl.contactId = p.contactId
        LEFT JOIN Names n ON n.contactId = p.contactId
        WHERE c.collectionId = 1
          AND c.deleted IS NULL
          AND c.isDeactivated = 0
          AND (c.changeFlags & 4) = 0
    """.trimIndent()

    private val NUMBERS_QUERY_LEGACY = """
        SELECT p.phoneNumber, p.normalizedNumber, c.displayLabel, c.firstName, c.lastName
        FROM PhoneNumbers p
        JOIN Contacts c ON c.contactId = p.contactId
        WHERE c.syncTarget = 'aggregate' AND c.isDeactivated = 0
    """.trimIndent()
}
