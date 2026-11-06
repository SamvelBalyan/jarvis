package com.example.jarvis

import java.util.Calendar

/** Pure-Kotlin text parsing (no Android classes) so it can be tested on a PC. */
object Parsers {

    // ------------------------------------------------------------ speech clean-up

    private val ones = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)
    private val ordinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12,
        "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16,
        "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19, "twentieth" to 20, "thirtieth" to 30
    )

    /** "alarm at seven thirty a m" -> "alarm at 7 30 am", "p c" -> "pc", "twenty first" -> "21". */
    fun normalizeSpeech(text: String): String {
        val w = text.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < w.size) {
            val a = w[i]
            val nx = if (i + 1 < w.size) w[i + 1] else null
            if (a == "oh" && nx != null && out.isNotEmpty() && out.last().all { it.isDigit() }) {
                val n = ones[nx]
                if (n != null && n in 1..9) {
                    out.add("0$n")
                    i += 2
                    continue
                }
            }
            val t = tens[a]
            if (t != null) {
                var n = t
                val u = if (nx != null) (ones[nx] ?: ordinals[nx]) else null
                if (u != null && u in 1..9) {
                    n += u
                    i++
                }
                out.add(n.toString())
            } else if (ones.containsKey(a)) {
                out.add(ones.getValue(a).toString())
            } else if (ordinals.containsKey(a)) {
                out.add(ordinals.getValue(a).toString())
            } else {
                out.add(a)
            }
            i++
        }
        return out.joinToString(" ")
            .replace(Regex("\\b([ap]) m\\b"), "$1m")
            .replace(Regex("\\bp c\\b"), "pc")
    }

    // ------------------------------------------------------------ search

    data class Search(val query: String, val engine: String)

    /** "open google and search for cats", "search cats on youtube", "google cats" */
    fun parseSearch(text: String): Search? {
        val s = text.trim()
        val engine = if (Regex("\\byoutube\\b").containsMatchIn(s)) "youtube" else "google"
        val m = Regex("\\b(?:search|look up|lookup)\\b(?: (?:for|up|about))? (.+)$").find(s)
            ?: Regex("^(?:hey )?google (?:for )?(.+)$").find(s)
            ?: return null
        var q = m.groupValues[1].trim()
        q = q.replace(Regex("^(?:google|youtube)(?: for)? "), "")
        q = q.replace(Regex(" (?:on|in) (?:my |the )?(?:pc|computer|laptop)$"), "")
        q = q.replace(Regex(" (?:on|in|using|with) (?:google|youtube)$"), "")
        q = q.trim()
        return if (q.isEmpty()) null else Search(q, engine)
    }

    // ------------------------------------------------------------ alarm

    data class Alarm(val hour: Int, val minute: Int, val label: String?, val days: List<Int>)

    private val weekdays = mapOf(
        "sunday" to Calendar.SUNDAY, "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY
    )
    private val notLabels = setOf("tomorrow", "today", "tonight", "me", "everyday", "every day")

    fun parseAlarm(text: String): Alarm {
        var s = " " + text.lowercase().trim() + " "
        var label: String? = null

        // "... called gym", "... named morning run at 6 am"
        val named = Regex("\\b(?:called|named|labeled|labelled|titled|label|name) (.+?)(?= (?:at|for|to|every) \\d| every\\b|$)").find(s.trimEnd())
        if (named != null) {
            label = named.groupValues[1].trim()
            s = s.replaceRange(named.range.first + 0, named.range.last + 1 + 0, " ")
        }
        // "alarm for gym at 7"
        if (label == null) {
            val forName = Regex("\\b(?:alarm|wake me up) for ([a-z][a-z ]*?) at \\d").find(s)
            if (forName != null && forName.groupValues[1].trim() !in notLabels) {
                label = forName.groupValues[1].trim()
                s = s.replaceRange(forName.groups[1]!!.range, " ")
            }
        }
        // "alarm at 7 am for gym"
        if (label == null) {
            val tail = Regex(" for ([a-z][a-z ]*)$").find(s.trimEnd())
            if (tail != null && tail.groupValues[1].trim() !in notLabels) {
                label = tail.groupValues[1].trim()
                s = s.trimEnd().removeRange(tail.range) + " "
            }
        }

        // repeat days
        val days = ArrayList<Int>()
        if (Regex("\\bevery ?day\\b|\\bdaily\\b").containsMatchIn(s)) {
            days.addAll(weekdays.values)
        } else if (Regex("\\bweekdays?\\b").containsMatchIn(s)) {
            days.addAll(listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY))
        } else {
            for ((name, d) in weekdays) {
                if (Regex("\\bevery $name\\b|\\b${name}s\\b").containsMatchIn(s)) days.add(d)
            }
        }

        s = s.replace(Regex("\\b(\\d{1,2})(\\d{2})\\b"), "$1:$2") // 730 -> 7:30
        val m = Regex("(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(s)
            ?: throw IllegalArgumentException("Say a time, e.g. \"alarm at 7:30 am\"")
        var hour = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[3]
        if (ampm.startsWith("p") && hour < 12) hour += 12
        if (ampm.startsWith("a") && hour == 12) hour = 0
        if (ampm.isEmpty() && hour < 12 && Regex("\\b(?:evening|afternoon|tonight)\\b").containsMatchIn(s)) hour += 12
        if (hour !in 0..23 || min !in 0..59) throw IllegalArgumentException("That time doesn't look right")
        return Alarm(hour, min, label?.takeIf { it.isNotEmpty() }, days)
    }

    // ------------------------------------------------------------ calendar event / reminder

    data class Event(val title: String, val start: Long, val allDay: Boolean)

    private val monthNames = listOf(
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december"
    )
    private const val MONTH_RE =
        "(january|february|march|april|may|june|july|august|september|october|november|december|sept)"

    private fun monthIndex(w: String): Int = if (w == "sept") 8 else monthNames.indexOf(w)

    fun parseEvent(text: String, now: Calendar = Calendar.getInstance()): Event {
        var s = " " + text.lowercase().trim() + " "
        val day = now.clone() as Calendar
        day.set(Calendar.HOUR_OF_DAY, 0)
        day.set(Calendar.MINUTE, 0)
        day.set(Calendar.SECOND, 0)
        day.set(Calendar.MILLISECOND, 0)
        val today = day.clone() as Calendar
        var dateFound = false
        var hour = -1
        var minute = 0
        var relativeStart: Long? = null

        fun cut(m: MatchResult) {
            s = s.replaceRange(m.range, " ")
        }

        // in 2 hours / in 30 minutes
        Regex("\\bin (\\d+) (hour|hours|minute|minutes|min|mins)\\b").find(s)?.let { m ->
            val n = m.groupValues[1].toInt()
            val ms = if (m.groupValues[2].startsWith("h")) n * 3_600_000L else n * 60_000L
            relativeStart = now.timeInMillis + ms
            cut(m)
        }

        // relative dates
        Regex("\\bday after tomorrow\\b").find(s)?.let {
            day.add(Calendar.DAY_OF_YEAR, 2); dateFound = true; cut(it)
        }
        if (!dateFound) Regex("\\btomorrow\\b").find(s)?.let {
            day.add(Calendar.DAY_OF_YEAR, 1); dateFound = true; cut(it)
        }
        if (!dateFound) Regex("\\b(?:today|tonight)\\b").find(s)?.let {
            dateFound = true; cut(it)
        }
        if (!dateFound) Regex("\\bin (\\d+) (day|days|week|weeks)\\b").find(s)?.let { m ->
            val n = m.groupValues[1].toInt()
            day.add(Calendar.DAY_OF_YEAR, if (m.groupValues[2].startsWith("w")) n * 7 else n)
            dateFound = true; cut(m)
        }
        if (!dateFound) Regex("\\bnext week\\b").find(s)?.let {
            day.add(Calendar.DAY_OF_YEAR, 7); dateFound = true; cut(it)
        }

        // "20 november", "november 20", "the 20th of november 2027"
        if (!dateFound) {
            val a = Regex("(?:\\bon )?(?:\\bthe )?\\b(\\d{1,2})(?:st|nd|rd|th)? (?:of )?$MONTH_RE\\b(?: (\\d{4}))?").find(s)
            val b = Regex("(?:\\bon )?\\b$MONTH_RE (?:the )?(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,? (\\d{4}))?").find(s)
            var dd = -1
            var mm = -1
            var yy = -1
            var mr: MatchResult? = null
            if (a != null) {
                dd = a.groupValues[1].toInt()
                mm = monthIndex(a.groupValues[2])
                yy = a.groupValues[3].ifEmpty { "-1" }.toInt()
                mr = a
            } else if (b != null) {
                mm = monthIndex(b.groupValues[1])
                dd = b.groupValues[2].toInt()
                yy = b.groupValues[3].ifEmpty { "-1" }.toInt()
                mr = b
            }
            if (mr != null && dd in 1..31 && mm >= 0) {
                val y = if (yy > 0) yy else today.get(Calendar.YEAR)
                day.set(y, mm, dd)
                if (yy <= 0 && day.before(today)) day.set(Calendar.YEAR, y + 1)
                dateFound = true
                cut(mr)
            }
        }

        // 20.11 or 20/11/2027 (day first)
        if (!dateFound) Regex("(?<!at )\\b(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?\\b").find(s)?.let { m ->
            val dd = m.groupValues[1].toInt()
            val mm = m.groupValues[2].toInt()
            if (dd in 1..31 && mm in 1..12) {
                var yy = m.groupValues[3].ifEmpty { "-1" }.toInt()
                if (yy in 0..99) yy += 2000
                val y = if (yy > 0) yy else today.get(Calendar.YEAR)
                day.set(y, mm - 1, dd)
                if (yy <= 0 && day.before(today)) day.set(Calendar.YEAR, y + 1)
                dateFound = true
                cut(m)
            }
        }

        // weekday names
        if (!dateFound) Regex("(?:\\b(?:on|next|this|coming) )?\\b(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\\b").find(s)?.let { m ->
            val target = weekdays.getValue(m.groupValues[1])
            var diff = (target - today.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (diff == 0) diff = 7
            day.add(Calendar.DAY_OF_YEAR, diff)
            dateFound = true
            cut(m)
        }

        // time of day
        if (relativeStart == null) {
            val t1 = Regex("(?:\\bat )?\\b(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])").find(s)
            val t2 = Regex("\\bat (\\d{1,2})(?:[:.\\s](\\d{2}))?\\b").find(s)
            val noon = Regex("\\bnoon\\b").find(s)
            val midnight = Regex("\\bmidnight\\b").find(s)
            if (t1 != null) {
                hour = t1.groupValues[1].toInt()
                minute = t1.groupValues[2].ifEmpty { "0" }.toInt()
                val ampm = t1.groupValues[3]
                if (ampm.startsWith("p") && hour < 12) hour += 12
                if (ampm.startsWith("a") && hour == 12) hour = 0
                cut(t1)
            } else if (t2 != null) {
                hour = t2.groupValues[1].toInt()
                minute = t2.groupValues[2].ifEmpty { "0" }.toInt()
                if (hour in 1..6) hour += 12 // "at 5" almost always means 5 pm for a reminder
                cut(t2)
            } else if (noon != null) {
                hour = 12; cut(noon)
            } else if (midnight != null) {
                hour = 0; cut(midnight)
            }
            if (hour !in -1..23 || minute !in 0..59) throw IllegalArgumentException("That time doesn't look right")
        }

        val rs = relativeStart
        if (rs != null) return Event(cleanTitle(s), rs, false)
        if (!dateFound && hour < 0) throw IllegalArgumentException("Say when, e.g. \"tomorrow at 5 pm\"")

        val start = day.clone() as Calendar
        if (hour >= 0) {
            start.set(Calendar.HOUR_OF_DAY, hour)
            start.set(Calendar.MINUTE, minute)
            if (!dateFound && start.timeInMillis <= now.timeInMillis) start.add(Calendar.DAY_OF_YEAR, 1)
        }
        return Event(cleanTitle(s), start.timeInMillis, hour < 0)
    }

    private fun cleanTitle(raw: String): String {
        var s = " $raw "
        s = s.replace(Regex("\\b(?:in|on|to|into|at|for) (?:my |the )?(?:google )?calendar\\b"), " ")
        s = s.replace(
            Regex("^\\s*(?:please )?(?:add|put|create|set|make|schedule|save)?\\s*(?:me )?(?:an? |the |new )*(?:reminder|event|note)?\\s*"),
            " "
        )
        s = s.replace(Regex("^\\s*remind me\\s*(?:to |about |that )?"), " ")
        var prev: String
        do {
            prev = s
            s = s.replace(Regex("^\\s*(?:called|named|titled|for|to|about|that|saying)\\b\\s*"), " ")
            s = s.replace(Regex("\\s*\\b(?:on|at|for|in|by|the|this|next|coming|to|and)\\s*$"), " ")
        } while (s != prev)
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return "Reminder"
        return s.replaceFirstChar { it.uppercase() }
    }
}
