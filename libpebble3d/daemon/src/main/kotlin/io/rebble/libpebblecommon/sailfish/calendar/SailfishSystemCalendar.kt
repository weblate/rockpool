package io.rebble.libpebblecommon.sailfish.calendar

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.calendar.CalendarEvent
import io.rebble.libpebblecommon.calendar.EventAttendee
import io.rebble.libpebblecommon.calendar.EventReminder
import io.rebble.libpebblecommon.calendar.NewCalendarEvent
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// KCalendarCore enum values as stored by mkcal.
private const val STATUS_TENTATIVE = 1
private const val STATUS_CONFIRMED = 2
private const val STATUS_CANCELLED = 6
private const val TRANSPARENCY_TRANSPARENT = 1

private const val DEFAULT_COLOR = 0xFF2196F3.toInt()

/**
 * [SystemCalendar] backed by Sailfish's mkcal SQLite database (read-only) plus the
 * jolla-calendar per-notebook settings. Equivalent of rockpoold's libmkcal integration.
 */
internal class SailfishSystemCalendar : SystemCalendar {
    private val logger = Logger.withTag("SailfishSystemCalendar")
    private val db = MkcalDatabase()

    override suspend fun getCalendars(): List<CalendarEntity> = withContext(Dispatchers.IO) {
        val notebooks = db.readNotebooks()
        val config = NemoCalendarSettings.load()
        notebooks.map { nb ->
            val visible = nb.visible && nb.uid !in config.excluded
            CalendarEntity(
                platformId = nb.uid,
                name = nb.name.ifBlank { nb.uid },
                ownerName = nb.account?.takeIf { it.isNotBlank() } ?: nb.name,
                ownerId = nb.account.orEmpty(),
                color = config.colorOverrides[nb.uid]
                    ?: parseCalendarColor(nb.color)
                    ?: DEFAULT_COLOR,
                enabled = visible,
                syncEvents = visible,
                visible = visible,
            )
        }
    }

    override suspend fun getCalendarEvents(
        calendar: CalendarEntity,
        startDate: Instant,
        endDate: Instant,
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val records = db.readEventRecords(calendar.platformId)
        try {
            buildEvents(records, calendar, startDate, endDate).sortedBy { it.startTime }
        } catch (e: Exception) {
            logger.w(e) { "failed to build events for calendar ${calendar.platformId}" }
            emptyList()
        }
    }

    override suspend fun enableSyncForCalendar(calendar: CalendarEntity) {
        // mkcal notebooks are always local; nothing to enable.
    }

    override fun registerForCalendarChanges(): Flow<Unit> = flow {
        var last = db.changeSignature()
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL)
            val signature = db.changeSignature()
            if (signature != last) {
                last = signature
                logger.d { "mkcal database changed" }
                emit(Unit)
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun hasPermission(): Boolean = db.available()

    override suspend fun createEvent(event: NewCalendarEvent): String? {
        logger.d { "createEvent not supported on Sailfish (read-only mkcal access)" }
        return null
    }

    override fun supportsPinActions(): Boolean = false

    private fun buildEvents(
        records: List<MkcalEventRecord>,
        calendar: CalendarEntity,
        windowStart: Instant,
        windowEnd: Instant,
    ): List<CalendarEvent> {
        val out = mutableListOf<CalendarEvent>()
        val byUid = records.groupBy { it.component.uid.ifBlank { "mkcal-${it.component.componentId}" } }
        for ((uid, rows) in byUid) {
            // Detached exceptions (RecurId != 0) replace the base occurrence they override.
            val detached = rows.filter { it.component.recurId != 0L }
            val replacedInstants = detached.map { it.component.recurId }.toSet()
            val replacedDates = detached.map {
                utcDate(if (it.component.recurIdLocal != 0L) it.component.recurIdLocal else it.component.recurId)
            }.toSet()

            for (rec in detached) {
                if (rec.component.status == STATUS_CANCELLED) continue
                val times = resolveTimes(rec.component) ?: continue
                if (times.start < windowEnd && times.end > windowStart) {
                    out += toEvent(rec, calendar, uid, times.start, times.end, times.allDay, recurs = true)
                }
            }

            for (rec in rows) {
                if (rec.component.recurId != 0L) continue
                if (rec.component.status == STATUS_CANCELLED) continue
                val times = resolveTimes(rec.component) ?: continue
                val recurring = rec.rules.any { it.ruleType == RULE_TYPE_RRULE } ||
                        rec.rdates.any { it.type == MkcalRdate.TYPE_RDATE || it.type == MkcalRdate.TYPE_RDATETIME }
                if (!recurring) {
                    if (times.start < windowEnd && times.end > windowStart) {
                        out += toEvent(
                            rec, calendar, uid, times.start, times.end, times.allDay,
                            recurs = false, occurrenceId = uid,
                        )
                    }
                } else {
                    out += expandOccurrences(
                        rec, calendar, uid, times, windowStart, windowEnd,
                        replacedInstants, replacedDates,
                    )
                }
            }
        }
        return out
    }

    private fun expandOccurrences(
        rec: MkcalEventRecord,
        calendar: CalendarEntity,
        uid: String,
        times: ResolvedTimes,
        windowStart: Instant,
        windowEnd: Instant,
        replacedInstants: Set<Long>,
        replacedDates: Set<LocalDate>,
    ): List<CalendarEvent> {
        val duration = times.end - times.start
        val zone = zoneFor(rec.component, times.allDay)
        val baseStart = javaInstant(times.start).atZone(zone)
        // An occurrence intersects the window if start < windowEnd && start + duration > windowStart.
        val low = javaInstant(windowStart - duration)
        val high = javaInstant(windowEnd)

        val starts = sortedSetOf<java.time.Instant>()
        // DTSTART is always the first occurrence (RFC 5545), even if it doesn't match the rule.
        baseStart.toInstant().let { if (it >= low && it < high) starts.add(it) }

        for (row in rec.rules.filter { it.ruleType == RULE_TYPE_RRULE }) {
            val rule = RecurrenceExpander.parse(row)
            if (rule == null) {
                logger.d { "unsupported RRULE for $uid (freq=${row.frequency}); base occurrence only" }
                continue
            }
            RecurrenceExpander.expand(rule, baseStart, low, high).forEach { starts.add(it.toInstant()) }
        }
        for (rd in rec.rdates) {
            when (rd.type) {
                MkcalRdate.TYPE_RDATETIME -> java.time.Instant.ofEpochSecond(rd.date)
                MkcalRdate.TYPE_RDATE ->
                    utcDate(if (rd.dateLocal != 0L) rd.dateLocal else rd.date).atStartOfDay(zone).toInstant()
                else -> null
            }?.let { if (it >= low && it < high) starts.add(it) }
        }
        for (row in rec.rules.filter { it.ruleType == RULE_TYPE_EXRULE }) {
            RecurrenceExpander.parse(row)?.let { rule ->
                RecurrenceExpander.expand(rule, baseStart, low, high).forEach { starts.remove(it.toInstant()) }
            }
        }

        val exInstants = rec.rdates
            .filter { it.type == MkcalRdate.TYPE_XDATETIME }
            .map { it.date }
            .toSet()
        val exDates = rec.rdates
            .filter { it.type == MkcalRdate.TYPE_XDATE }
            .map { utcDate(if (it.dateLocal != 0L) it.dateLocal else it.date) }
            .toSet()

        return starts.mapNotNull { occStart ->
            val occDate = occStart.atZone(zone).toLocalDate()
            if (occStart.epochSecond in exInstants) return@mapNotNull null
            if (occDate in exDates) return@mapNotNull null
            if (occStart.epochSecond in replacedInstants) return@mapNotNull null
            if (times.allDay && occDate in replacedDates) return@mapNotNull null
            val start = Instant.fromEpochMilliseconds(occStart.toEpochMilli())
            toEvent(
                rec, calendar, uid, start, start + duration, times.allDay,
                recurs = true, occurrenceId = "$uid#${occStart.epochSecond}",
            )
        }
    }

    private fun toEvent(
        rec: MkcalEventRecord,
        calendar: CalendarEntity,
        uid: String,
        start: Instant,
        end: Instant,
        allDay: Boolean,
        recurs: Boolean,
        occurrenceId: String = "$uid#${start.epochSeconds}",
    ): CalendarEvent {
        val c = rec.component
        return CalendarEvent(
            id = occurrenceId,
            calendarId = calendar.platformId,
            title = c.summary.ifBlank { "Untitled event" },
            description = c.description,
            location = c.location?.takeIf { it.isNotBlank() },
            startTime = start,
            endTime = end,
            allDay = allDay,
            attendees = rec.attendees.map { toAttendee(it, calendar.ownerId) },
            recurs = recurs,
            reminders = toReminders(rec, recurs),
            availability = if (c.transparency == TRANSPARENCY_TRANSPARENT) {
                CalendarEvent.Availability.Free
            } else {
                CalendarEvent.Availability.Busy
            },
            status = when (c.status) {
                STATUS_TENTATIVE -> CalendarEvent.Status.Tentative
                STATUS_CONFIRMED -> CalendarEvent.Status.Confirmed
                STATUS_CANCELLED -> CalendarEvent.Status.Cancelled
                else -> CalendarEvent.Status.None
            },
            baseEventId = uid,
        )
    }

    private fun toReminders(rec: MkcalEventRecord, recurs: Boolean): List<EventReminder> =
        rec.alarms.mapNotNull { alarm ->
            if (!alarm.enabled) return@mapNotNull null
            val relation = alarm.relation.orEmpty()
            val minutes = when {
                relation.contains("endTriggerRelation") -> null
                relation.contains("startTriggerRelation") -> (-alarm.offsetSecs / 60).toInt()
                // Absolute trigger only makes sense for the single base occurrence.
                alarm.dateTrigger != 0L ->
                    if (recurs) null else ((rec.component.dateStart - alarm.dateTrigger) / 60).toInt()
                else -> (-alarm.offsetSecs / 60).toInt()
            }
            minutes?.takeIf { it >= 0 }?.let { EventReminder(it) }
        }.distinct()

    private fun toAttendee(a: MkcalAttendee, ownerId: String): EventAttendee = EventAttendee(
        name = a.name?.takeIf { it.isNotBlank() },
        email = a.email?.takeIf { it.isNotBlank() },
        // KCalendarCore::Attendee::Role: 0=Req, 1=Opt, 2=Non, 3=Chair
        role = when (a.role) {
            0, 3 -> EventAttendee.Role.Required
            1 -> EventAttendee.Role.Optional
            else -> EventAttendee.Role.None
        },
        isOrganizer = a.isOrganizer,
        isCurrentUser = ownerId.isNotBlank() && a.email?.equals(ownerId, ignoreCase = true) == true,
        // KCalendarCore::Attendee::PartStat: 0=NeedsAction, 1=Accepted, 2=Declined, 3=Tentative
        attendanceStatus = when (a.partStat) {
            0 -> EventAttendee.AttendanceStatus.Invited
            1 -> EventAttendee.AttendanceStatus.Accepted
            2 -> EventAttendee.AttendanceStatus.Declined
            3 -> EventAttendee.AttendanceStatus.Tentative
            else -> EventAttendee.AttendanceStatus.None
        },
    )

    private data class ResolvedTimes(val start: Instant, val end: Instant, val allDay: Boolean)

    private fun resolveTimes(c: MkcalComponent): ResolvedTimes? {
        return if (c.allDay) {
            // Floating dates: the *Local columns hold the date encoded as UTC midnight;
            // render at local midnight like the Android impl.
            val zone = ZoneId.systemDefault()
            val startDate = utcDate(if (c.dateStartLocal != 0L) c.dateStartLocal else c.dateStart)
            val endSecs = if (c.dateEndDueLocal != 0L) c.dateEndDueLocal else c.dateEndDue
            // KCal all-day dtEnd is the last day inclusive; exclusive end = following midnight.
            val endDate = if (endSecs != 0L) utcDate(endSecs) else startDate
            val start = startDate.atStartOfDay(zone).toInstant()
            val end = maxOf(endDate, startDate).plusDays(1).atStartOfDay(zone).toInstant()
            ResolvedTimes(
                start = Instant.fromEpochMilliseconds(start.toEpochMilli()),
                end = Instant.fromEpochMilliseconds(end.toEpochMilli()),
                allDay = true,
            )
        } else {
            if (c.dateStart == 0L) return null
            val start = Instant.fromEpochSeconds(c.dateStart)
            val end = when {
                c.dateEndDue != 0L -> Instant.fromEpochSeconds(c.dateEndDue)
                c.durationSecs > 0 -> start + c.durationSecs.seconds
                else -> start
            }
            ResolvedTimes(start = start, end = maxOf(end, start), allDay = false)
        }
    }

    private fun zoneFor(c: MkcalComponent, allDay: Boolean): ZoneId {
        if (allDay) return ZoneId.systemDefault()
        val tz = c.startTimeZone?.takeIf { it.isNotBlank() && it != FLOATING_DATE }
            ?: return ZoneId.systemDefault()
        return try {
            ZoneId.of(tz)
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }
    }

    private fun utcDate(epochSeconds: Long): LocalDate =
        java.time.Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate()

    private fun javaInstant(instant: Instant): java.time.Instant =
        java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds())

    private companion object {
        val POLL_INTERVAL = 60.seconds
    }
}
