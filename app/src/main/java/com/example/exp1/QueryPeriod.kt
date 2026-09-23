package com.example.exp1

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class QueryPeriod(val start: String, val end: String, val label: String)

object QueryPeriodParser {

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val monthNames = listOf(
        "january","february","march","april","may","june",
        "july","august","september","october","november","december"
    )

    private fun dateMinus(daysAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return fmt.format(cal.time)
    }

    private fun monthRange(monthIndex: Int, year: Int): QueryPeriod {
        val cal = Calendar.getInstance()
        cal.set(year, monthIndex, 1, 0, 0, 0)
        val start = fmt.format(cal.time)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        val end = fmt.format(cal.time)
        val label = "${monthNames[monthIndex].replaceFirstChar { it.uppercase() }} $year"
        return QueryPeriod(start, end, label)
    }

    /** Looks for a month name, a standalone year, or a relative-time phrase in free text. Null if none found. */
    fun parse(text: String): QueryPeriod? {
        val lower = text.lowercase(Locale.US)
        val nowCal = Calendar.getInstance()
        val nowYear = nowCal.get(Calendar.YEAR)

        for ((index, name) in monthNames.withIndex()) {
            val short = name.substring(0, 3)
            if (lower.contains(name) || Regex("\\b$short\\b").containsMatchIn(lower)) {
                val year = Regex("(20\\d{2})").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: nowYear
                return monthRange(index, year)
            }
        }

        val today = fmt.format(nowCal.time)
        when {
            "today" in lower -> return QueryPeriod(today, today, "Today")
            "yesterday" in lower -> { val d = dateMinus(1); return QueryPeriod(d, d, "Yesterday") }
            "this week" in lower -> return QueryPeriod(dateMinus(6), today, "This week")
            "last week" in lower -> return QueryPeriod(dateMinus(13), dateMinus(7), "Last week")
            "this month" in lower -> return monthRange(nowCal.get(Calendar.MONTH), nowYear)
            "last month" in lower -> {
                val cal = Calendar.getInstance(); cal.add(Calendar.MONTH, -1)
                return monthRange(cal.get(Calendar.MONTH), cal.get(Calendar.YEAR))
            }
            "this year" in lower -> return QueryPeriod("$nowYear-01-01", "$nowYear-12-31", "$nowYear")
            "last year" in lower -> { val y = nowYear - 1; return QueryPeriod("$y-01-01", "$y-12-31", "$y") }
        }

        // Specific single date: "September 10", "Sept 10 2026", "10 September"
        for ((index, name) in monthNames.withIndex()) {
            val short = name.substring(0, 3)
            val dayMatch = Regex("(?:$name|$short)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?").find(lower)
                ?: Regex("(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:$name|$short)").find(lower)
            if (dayMatch != null) {
                val day = dayMatch.groupValues[1].toIntOrNull() ?: continue
                val year = Regex("(20\\d{2})").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: nowYear
                val cal = Calendar.getInstance()
                cal.set(year, index, day, 0, 0, 0)
                val dateStr = fmt.format(cal.time)
                val label = "${name.replaceFirstChar { it.uppercase() }} $day, $year"
                return QueryPeriod(dateStr, dateStr, label)
            }
        }

        // Numeric date: "2026-09-10" or "09/10/2026"
        Regex("(20\\d{2})-(\\d{1,2})-(\\d{1,2})").find(lower)?.let { m ->
            val (y, mo, d) = m.destructured
            val dateStr = String.format(Locale.US, "%04d-%02d-%02d", y.toInt(), mo.toInt(), d.toInt())
            return QueryPeriod(dateStr, dateStr, dateStr)
        }
        Regex("(\\d{1,2})/(\\d{1,2})/(20\\d{2})").find(lower)?.let { m ->
            val (mo, d, y) = m.destructured
            val dateStr = String.format(Locale.US, "%04d-%02d-%02d", y.toInt(), mo.toInt(), d.toInt())
            return QueryPeriod(dateStr, dateStr, dateStr)
        }

        // Standalone year, e.g. "analyze 2025" or "progress in 2024"
        Regex("\\b(20\\d{2})\\b").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { yr ->
            return QueryPeriod("$yr-01-01", "$yr-12-31", "$yr")
        }

        return null
    }

    /** True only if the user's wording explicitly asks for a chart/visual. */
    fun wantsChart(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return listOf("chart", "pie", "graph", "visual", "breakdown", "show me", "diagram").any { it in lower }
    }
}