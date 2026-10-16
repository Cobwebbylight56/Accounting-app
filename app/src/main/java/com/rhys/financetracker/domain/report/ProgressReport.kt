package com.rhys.financetracker.domain.report

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * How a month went — or how this one is going — against the one before:
 * better, much the same, or needing work, with what moved and what to do
 * about it. Made at the start of each month and halfway through, for the
 * household together and for each person, and kept.
 */
data class ProgressReport(
    /** The period and whose it is: one report per pair, ever. */
    val key: String,
    val kind: Kind,
    /** "Together", or a person's first name. */
    val scope: String,
    /** The month reported on. */
    val month: YearMonth,
    val madeOn: LocalDate,
    val verdict: Verdict,
    val headline: String,
    val lines: List<Line>,
    val tips: List<String>,
) {
    enum class Kind(val label: String) {
        /** Made at the start of a month, about the month just gone. */
        MONTH("How the month went"),

        /** Made halfway through, about the month so far. */
        MID_MONTH("Halfway through the month"),
    }

    enum class Verdict(val label: String) {
        BETTER("Doing better"),
        STEADY("Much the same"),
        WORSE("Needs improving"),
    }

    /** One figure, now and before; [better] null when it barely moved or is just for information. */
    data class Line(val label: String, val nowMinor: Long, val beforeMinor: Long, val better: Boolean?)

    /** "How September went", "October, halfway". */
    val title: String
        get() = when (kind) {
            Kind.MONTH -> "How ${monthName(month)} went"
            Kind.MID_MONTH -> "${monthName(month)}, halfway"
        }
}

/** The figures compared, for one stretch of time. */
data class ProgressFigures(
    val moneyInMinor: Long,
    val spentMinor: Long,
    /** Put aside into savings, less what came back out. */
    val savedMinor: Long,
    /** How much what's owed on cards and loans grew over the stretch; negative when it came down. */
    val owedChangeMinor: Long,
    /** Spending by category name. */
    val byCategory: Map<String, Long> = emptyMap(),
) {
    /** What came in, less what went out. */
    val leftOverMinor: Long get() = moneyInMinor - spentMinor

    val isEmpty: Boolean get() = moneyInMinor == 0L && spentMinor == 0L && savedMinor == 0L
}

/** When reports are due, and the stretches each one compares. */
object ProgressSchedule {

    data class Period(
        val kind: ProgressReport.Kind,
        /** Shared by every report of this period. */
        val key: String,
        val month: YearMonth,
        val now: ClosedRange<LocalDate>,
        val before: ClosedRange<LocalDate>,
        /** "August", "the same days of September". */
        val beforeName: String,
        /** For halfway: the whole of the month before, to say where this one is heading. */
        val beforeWholeMonth: ClosedRange<LocalDate>? = null,
    )

    /** Before the 15th, the month just gone; from the 15th, the first half of this one. */
    fun current(today: LocalDate): Period {
        val thisMonth = YearMonth.from(today)
        return if (today.dayOfMonth < HALFWAY) {
            val month = thisMonth.minusMonths(1)
            val previous = month.minusMonths(1)
            Period(
                kind = ProgressReport.Kind.MONTH,
                key = "$month-month",
                month = month,
                now = month.atDay(1)..month.atEndOfMonth(),
                before = previous.atDay(1)..previous.atEndOfMonth(),
                beforeName = monthName(previous),
            )
        } else {
            val previous = thisMonth.minusMonths(1)
            Period(
                kind = ProgressReport.Kind.MID_MONTH,
                key = "$thisMonth-mid",
                month = thisMonth,
                now = thisMonth.atDay(1)..thisMonth.atDay(HALFWAY - 1),
                before = previous.atDay(1)..previous.atDay(HALFWAY - 1),
                beforeName = "the same days of ${monthName(previous)}",
                beforeWholeMonth = previous.atDay(1)..previous.atEndOfMonth(),
            )
        }
    }

    /** The day the second report of a month is due. */
    const val HALFWAY = 15
}

/** Turns two stretches' figures into a report. */
object ProgressReporter {

    /**
     * @return null when there's nothing to compare — no money moved in one
     *   stretch or the other.
     */
    fun build(
        period: ProgressSchedule.Period,
        scope: String,
        scopeKey: String,
        now: ProgressFigures,
        before: ProgressFigures,
        madeOn: LocalDate,
        /** For halfway: what the whole of last month came to. */
        beforeWholeMonthSpentMinor: Long? = null,
    ): ProgressReport? {
        if (now.isEmpty || before.isEmpty) return null
        val halfway = period.kind == ProgressReport.Kind.MID_MONTH
        val lines = buildList {
            add(ProgressReport.Line(if (halfway) "Spent so far" else "Spent", now.spentMinor, before.spentMinor, lowerIsBetter(now.spentMinor, before.spentMinor)))
            add(ProgressReport.Line(if (halfway) "Put aside so far" else "Put aside", now.savedMinor, before.savedMinor, higherIsBetter(now.savedMinor, before.savedMinor)))
            if (!halfway) {
                // Halfway through, pay day decides these more than anything done.
                add(ProgressReport.Line("Money in", now.moneyInMinor, before.moneyInMinor, null))
                add(ProgressReport.Line("Left over", now.leftOverMinor, before.leftOverMinor, higherIsBetter(now.leftOverMinor, before.leftOverMinor)))
            }
            if (now.owedChangeMinor != 0L || before.owedChangeMinor != 0L) {
                add(
                    ProgressReport.Line(
                        "Owed on cards and loans",
                        now.owedChangeMinor,
                        before.owedChangeMinor,
                        when {
                            now.owedChangeMinor <= -SMALLEST -> true
                            now.owedChangeMinor >= SMALLEST -> false
                            else -> null
                        },
                    ),
                )
            }
        }
        val score = lines.count { it.better == true } - lines.count { it.better == false }
        val verdict = when {
            score > 0 -> ProgressReport.Verdict.BETTER
            score < 0 -> ProgressReport.Verdict.WORSE
            else -> ProgressReport.Verdict.STEADY
        }
        return ProgressReport(
            key = "${period.key}|$scopeKey",
            kind = period.kind,
            scope = scope,
            month = period.month,
            madeOn = madeOn,
            verdict = verdict,
            headline = headline(verdict, lines, period.beforeName),
            lines = lines,
            tips = tips(lines, now, before, period, beforeWholeMonthSpentMinor),
        )
    }

    private fun headline(verdict: ProgressReport.Verdict, lines: List<ProgressReport.Line>, beforeName: String): String {
        val spent = lines.first()
        val saved = lines[1]
        val spentMove = spent.nowMinor - spent.beforeMinor
        val savedMove = saved.nowMinor - saved.beforeMinor
        val spentText = when {
            spent.better == true -> "${money(-spentMove)} less spent"
            spent.better == false -> "${money(spentMove)} more spent"
            else -> "spending much the same"
        }
        val savedText = when {
            saved.better == true -> "${money(savedMove)} more put aside"
            saved.better == false -> "${money(-savedMove)} less put aside"
            else -> null
        }
        val lead = when (verdict) {
            ProgressReport.Verdict.BETTER -> "Better than $beforeName"
            ProgressReport.Verdict.WORSE -> "Not as good as $beforeName"
            ProgressReport.Verdict.STEADY -> "Much like $beforeName"
        }
        return "$lead: " + listOfNotNull(spentText, savedText).joinToString(" and ") + "."
    }

    private fun tips(
        lines: List<ProgressReport.Line>,
        now: ProgressFigures,
        before: ProgressFigures,
        period: ProgressSchedule.Period,
        beforeWholeMonthSpentMinor: Long?,
    ): List<String> = buildList {
        val byLabel = lines.associateBy { it.label.removeSuffix(" so far") }
        // Which categories moved most, either way.
        val moves = (now.byCategory.keys + before.byCategory.keys).associateWith { name ->
            (now.byCategory[name] ?: 0L) - (before.byCategory[name] ?: 0L)
        }
        val rises = moves.filter { it.value >= CATEGORY_MOVE }.entries.sortedByDescending { it.value }.take(2)
        val falls = moves.filter { it.value <= -CATEGORY_MOVE }.entries.sortedBy { it.value }.take(2)

        if (byLabel["Spent"]?.better == false) {
            if (rises.isNotEmpty()) {
                add("The biggest rises: " + rises.joinToString(", ") { "${it.key} up ${money(it.value)}" } + ".")
            }
            add("Look through Spending → Where it went for anything you could cut back.")
        } else if (falls.isNotEmpty()) {
            add("Well done on " + falls.joinToString(" and ") { "${it.key} (down ${money(-it.value)})" } + ".")
        }
        if (byLabel["Put aside"]?.better == false) {
            add("A regular payment into savings on pay day puts money aside before it can be spent.")
        } else if (byLabel["Put aside"]?.better == true) {
            add("More put aside than before — keep it going.")
        }
        val owed = byLabel["Owed on cards and loans"]
        if (owed?.better == false) {
            add("What's owed grew ${money(owed.nowMinor)} — paying off more than goes on the cards each month brings it down.")
        } else if (owed?.better == true) {
            add("What's owed came down ${money(-owed.nowMinor)}.")
        }
        if (byLabel["Left over"]?.better == false) {
            add("${money(byLabel.getValue("Left over").beforeMinor - byLabel.getValue("Left over").nowMinor)} less left over than ${period.beforeName}.")
        }
        // Halfway: where the month is heading at this rate.
        if (period.kind == ProgressReport.Kind.MID_MONTH && beforeWholeMonthSpentMinor != null && beforeWholeMonthSpentMinor > 0L) {
            val days = period.now.endInclusive.dayOfMonth
            val length = period.month.lengthOfMonth()
            val heading = now.spentMinor * length / days.coerceAtLeast(1)
            val last = monthName(period.month.minusMonths(1))
            add(
                "At this rate the month comes to about ${money(heading)}, against ${money(beforeWholeMonthSpentMinor)} for all of $last.",
            )
        }
    }

    /** A move smaller than this — or than 5% — is "much the same". */
    private fun moved(now: Long, before: Long): Boolean =
        abs(now - before) >= maxOf(SMALLEST, abs(before) * SAME_PERCENT / 100)

    private fun lowerIsBetter(now: Long, before: Long): Boolean? = if (!moved(now, before)) null else now < before

    private fun higherIsBetter(now: Long, before: Long): Boolean? = if (!moved(now, before)) null else now > before

    /** £10. */
    private const val SMALLEST = 1_000L
    private const val SAME_PERCENT = 5L

    /** A category has to move £20 to be worth naming. */
    private const val CATEGORY_MOVE = 2_000L
}

/** Reports as kept in the settings: one a line, fields and lists split by control characters no name holds. */
object ProgressReportCodec {

    fun write(reports: List<ProgressReport>): String =
        reports.joinToString(RECORD) { r ->
            listOf(
                r.key,
                r.kind.name,
                r.scope,
                r.month.toString(),
                r.madeOn.toString(),
                r.verdict.name,
                r.headline,
                r.lines.joinToString(ITEM) { line ->
                    listOf(line.label, line.nowMinor.toString(), line.beforeMinor.toString(), line.better?.toString().orEmpty())
                        .joinToString(PART)
                },
                r.tips.joinToString(ITEM),
            ).joinToString(FIELD) { it.replace(RECORD, " ").replace(FIELD, " ") }
        }

    fun read(stored: String?): List<ProgressReport> =
        stored.orEmpty().split(RECORD).mapNotNull { record ->
            val f = record.split(FIELD)
            if (f.size != FIELDS) return@mapNotNull null
            runCatching {
                ProgressReport(
                    key = f[0],
                    kind = ProgressReport.Kind.valueOf(f[1]),
                    scope = f[2],
                    month = YearMonth.parse(f[3]),
                    madeOn = LocalDate.parse(f[4]),
                    verdict = ProgressReport.Verdict.valueOf(f[5]),
                    headline = f[6],
                    lines = f[7].split(ITEM).filter { it.isNotEmpty() }.map { item ->
                        val p = item.split(PART)
                        ProgressReport.Line(p[0], p[1].toLong(), p[2].toLong(), p.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toBoolean())
                    },
                    tips = f[8].split(ITEM).filter { it.isNotEmpty() },
                )
            }.getOrNull()
        }

    private const val RECORD = "\u001E"
    private const val FIELD = "\u001F"
    private const val ITEM = "\u001D"
    private const val PART = "\u001C"
    private const val FIELDS = 9
}

internal fun monthName(month: YearMonth): String = month.month.getDisplayName(TextStyle.FULL, Locale.UK)

/** "£1,234", whole pounds: a report is read at a glance. */
internal fun money(minor: Long): String = "£" + String.format(Locale.UK, "%,d", abs(minor) / 100)
