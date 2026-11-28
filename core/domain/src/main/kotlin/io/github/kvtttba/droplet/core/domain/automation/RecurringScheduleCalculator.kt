package io.github.kvtttba.droplet.core.domain.automation

import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.RecurringRule
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Pure JVM recurring rule schedule calculator supporting DAILY, WEEKLY, MONTHLY (with safe
 * month-end clamping for 29–31), and YEARLY (with safe leap-year Feb 29 clamping).
 */
object RecurringScheduleCalculator {

    val DEFAULT_ZONE_ID: ZoneId = ZoneId.systemDefault()
    const val MAX_CATCHUP_OCCURRENCES: Int = 12

    fun advanceDate(
        currentDate: LocalDate,
        frequency: RecurringFrequency,
        intervalCount: Int = 1,
        anchorDayOfMonth: Int? = null,
    ): LocalDate {
        require(intervalCount > 0) { "intervalCount must be positive, got $intervalCount" }
        val targetDayAnchor = (anchorDayOfMonth ?: currentDate.dayOfMonth).coerceIn(1, 31)
        return when (frequency) {
            RecurringFrequency.DAILY -> currentDate.plusDays(intervalCount.toLong())
            RecurringFrequency.WEEKLY -> currentDate.plusWeeks(intervalCount.toLong())
            RecurringFrequency.MONTHLY -> {
                val targetYearMonth = YearMonth.from(currentDate).plusMonths(intervalCount.toLong())
                val clampedDay = minOf(targetDayAnchor, targetYearMonth.lengthOfMonth())
                targetYearMonth.atDay(clampedDay)
            }
            RecurringFrequency.YEARLY -> {
                val targetYear = currentDate.year + intervalCount
                val targetYearMonth = YearMonth.of(targetYear, currentDate.month)
                val clampedDay = minOf(targetDayAnchor, targetYearMonth.lengthOfMonth())
                targetYearMonth.atDay(clampedDay)
            }
            RecurringFrequency.CRON -> throw IllegalArgumentException(
                "CRON frequency requires explicit schedule resolution",
            )
        }
    }

    fun computeNextRunAt(
        currentRunAtEpochMs: Long,
        frequency: RecurringFrequency,
        intervalCount: Int = 1,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
        anchorDayOfMonth: Int? = null,
    ): Long {
        val zoned = Instant.ofEpochMilli(currentRunAtEpochMs).atZone(zoneId)
        val nextDate = advanceDate(
            currentDate = zoned.toLocalDate(),
            frequency = frequency,
            intervalCount = intervalCount,
            anchorDayOfMonth = anchorDayOfMonth,
        )
        return nextDate.atTime(zoned.toLocalTime()).atZone(zoneId).toInstant().toEpochMilli()
    }

    fun isTerminated(rule: RecurringRule): Boolean =
        rule.endAt != null && rule.nextRunAt > rule.endAt!!

    fun isDue(
        rule: RecurringRule,
        asOfEpochMs: Long,
    ): Boolean = !rule.isPaused && !isTerminated(rule) && rule.nextRunAt <= asOfEpochMs

    fun isPendingConfirmation(
        rule: RecurringRule,
        asOfEpochMs: Long,
    ): Boolean = isDue(rule, asOfEpochMs) && !rule.autoPost

    /**
     * Collects all due execution timestamps up to [asOfEpochMs] (bounded by [maxOccurrences] and
     * `rule.endAt`), together with the resulting [nextRunAtAfter] timestamp to persist.
     */
    fun collectDuePlan(
        rule: RecurringRule,
        asOfEpochMs: Long,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
        maxOccurrences: Int = MAX_CATCHUP_OCCURRENCES,
    ): DueOccurrencePlan {
        require(maxOccurrences > 0) { "maxOccurrences must be positive" }
        if (!isDue(rule, asOfEpochMs)) {
            return DueOccurrencePlan(
                dueTimestamps = emptyList(),
                nextRunAtAfter = rule.nextRunAt,
            )
        }
        val anchorDay = Instant.ofEpochMilli(rule.nextRunAt).atZone(zoneId).dayOfMonth
        val dueList = mutableListOf<Long>()
        var cursor = rule.nextRunAt
        while (
            dueList.size < maxOccurrences &&
            cursor <= asOfEpochMs &&
            (rule.endAt == null || cursor <= rule.endAt!!)
        ) {
            dueList += cursor
            cursor = computeNextRunAt(
                currentRunAtEpochMs = cursor,
                frequency = rule.frequency,
                intervalCount = rule.intervalCount,
                zoneId = zoneId,
                anchorDayOfMonth = anchorDay,
            )
        }
        return DueOccurrencePlan(
            dueTimestamps = dueList,
            nextRunAtAfter = cursor,
        )
    }

    fun formatFrequencyLabel(
        frequency: RecurringFrequency,
        intervalCount: Int = 1,
    ): String = when (frequency) {
        RecurringFrequency.DAILY -> if (intervalCount == 1) "每天" else "每 $intervalCount 天"
        RecurringFrequency.WEEKLY -> if (intervalCount == 1) "每周" else "每 $intervalCount 周"
        RecurringFrequency.MONTHLY -> if (intervalCount == 1) "每月" else "每 $intervalCount 个月"
        RecurringFrequency.YEARLY -> if (intervalCount == 1) "每年" else "每 $intervalCount 年"
        RecurringFrequency.CRON -> "自定义周期"
    }
}

data class DueOccurrencePlan(
    val dueTimestamps: List<Long>,
    val nextRunAtAfter: Long,
)
