package cn.qingji.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

val ledgerZone: ZoneId = ZoneId.of("Asia/Shanghai")
enum class Period(val label: String) { DAY("日"), WEEK("周"), MONTH("月"), YEAR("年") }
data class DateRange(val start: LocalDate, val endExclusive: LocalDate) {
    fun contains(time: Long): Boolean = time >= start.atStartOfDay(ledgerZone).toInstant().toEpochMilli() && time < endExclusive.atStartOfDay(ledgerZone).toInstant().toEpochMilli()
    val label: String get() = "$start 至 ${endExclusive.minusDays(1)}"
}
data class SpendTotal(val expense: Long, val refund: Long, val count: Int) { val net: Long get() = expense - refund }
data class Breakdown(val key: String, val value: Long, val delta: Long)
data class Bucket(val label: String, val value: Long)
data class Report(val range: DateRange, val previous: DateRange, val yearAgo: DateRange,
    val total: SpendTotal, val previousTotal: SpendTotal, val yearAgoTotal: SpendTotal,
    val categories: List<Breakdown>, val merchants: List<Breakdown>, val buckets: List<Bucket>, val days: Long)

object Reports {
    fun fullRange(period: Period, date: LocalDate): DateRange = when (period) {
        Period.DAY -> DateRange(date, date.plusDays(1))
        Period.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { DateRange(it, it.plusWeeks(1)) }
        Period.MONTH -> date.withDayOfMonth(1).let { DateRange(it, it.plusMonths(1)) }
        Period.YEAR -> date.withDayOfYear(1).let { DateRange(it, it.plusYears(1)) }
    }
    fun shift(period: Period, date: LocalDate, offset: Long): LocalDate = when (period) {
        Period.DAY -> date.plusDays(offset); Period.WEEK -> date.plusWeeks(offset)
        Period.MONTH -> date.plusMonths(offset); Period.YEAR -> date.plusYears(offset)
    }
    private fun eligible(entries: List<Entry>, range: DateRange) = entries.filter {
        it.status == Status.CONFIRMED && it.currency == "CNY" && it.kind in listOf(Kind.EXPENSE, Kind.REFUND) && range.contains(it.occurredAt)
    }
    fun total(entries: List<Entry>, range: DateRange): SpendTotal {
        val selected = eligible(entries, range)
        return SpendTotal(selected.filter { it.kind == Kind.EXPENSE }.sumOf { it.amountFen },
            selected.filter { it.kind == Kind.REFUND }.sumOf { it.amountFen }, selected.count { it.kind == Kind.EXPENSE })
    }
    private fun net(entry: Entry): Long = if (entry.kind == Kind.REFUND) -entry.amountFen else entry.amountFen
    fun build(entries: List<Entry>, period: Period, anchor: LocalDate, today: LocalDate): Report {
        val full = fullRange(period, anchor)
        val current = !today.isBefore(full.start) && today.isBefore(full.endExclusive)
        val range = if (current) full.copy(endExclusive = today.plusDays(1)) else full
        val prevFull = fullRange(period, shift(period, full.start, -1))
        fun comparable(target: DateRange, yearly: Boolean): DateRange {
            if (!current) return target
            val end = when {
                yearly -> today.minusYears(1).plusDays(1)
                period == Period.MONTH -> target.start.withDayOfMonth(minOf(today.dayOfMonth, target.start.lengthOfMonth())).plusDays(1)
                period == Period.YEAR -> today.minusYears(1).plusDays(1)
                else -> target.start.plusDays(java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endExclusive))
            }
            return target.copy(endExclusive = minOf(end, target.endExclusive).coerceAtLeast(target.start.plusDays(1)))
        }
        val previous = comparable(prevFull, false)
        val yearFull = fullRange(period, anchor.minusYears(1))
        // For weeks, compare Monday-based weeks with the same number of covered days.
        val yearAgo = if (current && period == Period.WEEK) yearFull.copy(endExclusive = yearFull.start.plusDays(java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endExclusive))) else comparable(yearFull, true)
        val selected = eligible(entries, range)
        val previousEntries = eligible(entries, previous)
        fun breakdown(key: (Entry) -> String): List<Breakdown> {
            val a = selected.groupBy(key).mapValues { it.value.sumOf(::net) }
            val b = previousEntries.groupBy(key).mapValues { it.value.sumOf(::net) }
            return (a.keys + b.keys).map { Breakdown(it, a[it] ?: 0, (a[it] ?: 0) - (b[it] ?: 0)) }.sortedByDescending { it.value }
        }
        val byBucket = selected.groupBy {
            val dateTime = java.time.Instant.ofEpochMilli(it.occurredAt).atZone(ledgerZone)
            when (period) { Period.DAY -> dateTime.hour.toString(); Period.YEAR -> dateTime.monthValue.toString(); else -> dateTime.toLocalDate().toString() }
        }.mapValues { (_, list) -> list.sumOf(::net) }
        val buckets = when (period) {
            Period.DAY -> (0..23).map { hour -> Bucket("${hour}时", byBucket[hour.toString()] ?: 0) }
            Period.YEAR -> (1..range.endExclusive.minusDays(1).monthValue).map { month -> Bucket("${month}月", byBucket[month.toString()] ?: 0) }
            else -> generateSequence(range.start) { it.plusDays(1) }.takeWhile { it < range.endExclusive }.map { day -> Bucket("${day.dayOfMonth}日", byBucket[day.toString()] ?: 0) }.toList()
        }
        return Report(range, previous, yearAgo, total(entries, range), total(entries, previous), total(entries, yearAgo),
            breakdown { it.category.label }, breakdown { it.merchant }, buckets,
            java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endExclusive).coerceAtLeast(1))
    }
}
