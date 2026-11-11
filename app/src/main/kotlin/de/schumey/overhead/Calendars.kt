package de.schumey.overhead

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

/**
 * Appointments for the calendar widget. Two sources: the calendars on the phone (CalendarContract, which also holds
 * whatever iCloud or TimeTree sync into the phone) and ICS/webcal links (read-only, e.g. a shared iCloud calendar).
 */
object Calendars {
    class Event(
        val title: String,
        val start: Long,
        val end: Long,
        val allDay: Boolean,
        val color: Int,
        val calendar: String,
    )

    class DeviceCalendar(val id: Long, val name: String, val account: String, val color: Int, val visible: Boolean)

    /** [needsPermission]: nothing can be shown until calendar access is allowed (and no link is set up either). */
    class Data(val events: List<Event>, val needsPermission: Boolean, val hasSources: Boolean)

    const val WINDOW_DAYS = 14
    private const val DAY = 86_400_000L
    private const val ICS_TTL_MS = 30 * 60_000L
    private const val MAX_EVENTS = 200
    private val PALETTE = intArrayOf(0xFF7FB2E5.toInt(), 0xFFE8B02F.toInt(), 0xFF8BC48A.toInt(), 0xFFD98A5B.toInt(), 0xFFB79CE0.toInt())

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun startOfDay(t: Long): Long = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    /** Everything coming up in the next [WINDOW_DAYS] days that has not ended yet, earliest first. */
    fun load(ctx: Context, now: Long): Data {
        val prefs = Prefs(ctx)
        val from = startOfDay(now)
        val to = from + WINDOW_DAYS * DAY
        val out = ArrayList<Event>()
        val perm = hasPermission(ctx)
        if (perm) out += try { deviceEvents(ctx, from, to, prefs.calendarIds) } catch (e: Exception) { emptyList() }
        val urls = prefs.calendarUrls
        urls.forEachIndexed { i, url ->
            val text = cacheFile(ctx, url).takeIf { it.exists() }?.readText() ?: return@forEachIndexed
            out += try { parseIcs(text, from, to, PALETTE[i % PALETTE.size], sourceName(url)) } catch (e: Exception) { emptyList() }
        }
        val upcoming = out.filter { it.end > now || (it.end == it.start && it.start >= from && it.allDay) }
            .sortedWith(compareBy<Event>({ it.start }, { !it.allDay }, { it.title }))
            .take(MAX_EVENTS)
        return Data(upcoming, needsPermission = !perm && urls.isEmpty(), hasSources = perm || urls.isNotEmpty())
    }

    // ------------------------------------------------------------------ phone calendars

    fun deviceCalendars(ctx: Context): List<DeviceCalendar> {
        if (!hasPermission(ctx)) return emptyList()
        val list = ArrayList<DeviceCalendar>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.VISIBLE,
            ),
            null, null, CalendarContract.Calendars.ACCOUNT_NAME,
        )?.use { c ->
            while (c.moveToNext()) {
                list += DeviceCalendar(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3) or 0xFF000000.toInt(), c.getInt(4) != 0)
            }
        }
        return list
    }

    /** [ids] null: all calendars the phone shows. */
    fun deviceEvents(ctx: Context, from: Long, to: Long, ids: Set<Long>?): List<Event> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val list = ArrayList<Event>()
        ctx.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_COLOR, CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.VISIBLE,
            ),
            null, null, CalendarContract.Instances.BEGIN,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(5)
                if (ids != null) { if (id !in ids) continue } else if (c.getInt(7) == 0) continue
                val allDay = c.getInt(3) != 0
                // all-day events are stored as UTC midnights; show them on the local day
                val start = if (allDay) utcDayToLocal(c.getLong(1)) else c.getLong(1)
                val end = if (allDay) utcDayToLocal(c.getLong(2)) else c.getLong(2)
                list += Event(c.getString(0)?.takeIf { it.isNotBlank() } ?: "—", start, end, allDay, c.getInt(4) or 0xFF000000.toInt(), c.getString(6) ?: "")
            }
        }
        return list
    }

    private fun utcDayToLocal(ms: Long): Long {
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        return Calendar.getInstance().apply { clear(); set(u[Calendar.YEAR], u[Calendar.MONTH], u[Calendar.DAY_OF_MONTH]) }.timeInMillis
    }

    // ------------------------------------------------------------------ ICS links

    fun normalizeUrl(raw: String): String? {
        var u = raw.trim()
        if (u.startsWith("webcal://", true)) u = "https://" + u.substring(9)
        else if (u.startsWith("webcals://", true)) u = "https://" + u.substring(10)
        return if (u.startsWith("https://", true) || u.startsWith("http://", true)) u else null
    }

    fun sourceName(url: String): String {
        val host = try { Uri.parse(url).host.orEmpty() } catch (e: Exception) { "" }
        return if (host.contains("icloud.com")) "iCloud" else host.removePrefix("www.").ifEmpty { "ICS" }
    }

    private fun cacheFile(ctx: Context, url: String) = File(ctx.filesDir, "ics_${url.hashCode().toUInt()}.ics")

    /** Downloads the links whose copy is older than 30 minutes (or all if [force]d). Blocking: background threads only. */
    fun refreshLinks(ctx: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        for (url in Prefs(ctx).calendarUrls) {
            val f = cacheFile(ctx, url)
            if (!force && f.exists() && now - f.lastModified() < ICS_TTL_MS) continue
            try {
                val body = FlightApi.get(url, readTimeoutMs = 20_000).second
                if (body.contains("BEGIN:VCALENDAR")) f.writeText(body) // keep the old copy if the answer is not a calendar
            } catch (e: Exception) {
                // keep the old copy
            }
        }
    }

    /** Throws if the link cannot be read or is not a calendar. Used when a link is added. */
    fun fetchOnce(ctx: Context, url: String) {
        val body = FlightApi.get(url, readTimeoutMs = 20_000).second
        if (!body.contains("BEGIN:VCALENDAR")) throw java.io.IOException("not a calendar")
        cacheFile(ctx, url).writeText(body)
    }

    fun forgetLink(ctx: Context, url: String) {
        cacheFile(ctx, url).delete()
    }

    // ------------------------------------------------------------------ ICS parsing

    private class Prop(val params: Map<String, String>, val value: String)

    private class Parsed(val millis: Long, val allDay: Boolean, val tz: TimeZone)

    private class Rule(val freq: String, val interval: Int, val count: Int?, val until: Long?, val byDay: List<Int>)

    /** The events of an ICS text that fall into [from, to), repeating events expanded (daily/weekly/monthly/yearly rules). */
    fun parseIcs(text: String, from: Long, to: Long, color: Int, calName: String): List<Event> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\n[ \t]"), "").split('\n')
        val blocks = ArrayList<Map<String, List<Prop>>>()
        var cur: MutableMap<String, MutableList<Prop>>? = null
        for (line in lines) {
            when {
                line.equals("BEGIN:VEVENT", true) -> cur = HashMap()
                line.equals("END:VEVENT", true) -> { cur?.let { blocks += it }; cur = null }
                cur != null -> {
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val head = line.substring(0, colon).split(';')
                    val params = head.drop(1).mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it).uppercase() to p.substring(it + 1).trim('"') } }.toMap()
                    cur.getOrPut(head[0].uppercase()) { ArrayList() }.add(Prop(params, line.substring(colon + 1)))
                }
            }
        }
        // single changed occurrences of a repeating event replace the generated one
        val replaced = HashSet<String>()
        for (b in blocks) {
            val uid = b["UID"]?.firstOrNull()?.value ?: continue
            b["RECURRENCE-ID"]?.firstOrNull()?.let { r -> parseDt(r)?.let { replaced += "$uid|${it.millis}" } }
        }
        val out = ArrayList<Event>()
        for (b in blocks) {
            if (b["STATUS"]?.firstOrNull()?.value.equals("CANCELLED", true)) continue
            val start = b["DTSTART"]?.firstOrNull()?.let { parseDt(it) } ?: continue
            val endProp = b["DTEND"]?.firstOrNull()?.let { parseDt(it) }
            val durProp = b["DURATION"]?.firstOrNull()?.value?.let { parseDuration(it) }
            val dur = when {
                endProp != null -> endProp.millis - start.millis
                durProp != null -> durProp
                start.allDay -> DAY
                else -> 0L
            }.coerceAtLeast(0)
            val title = unescape(b["SUMMARY"]?.firstOrNull()?.value ?: "").ifBlank { "—" }
            val uid = b["UID"]?.firstOrNull()?.value
            val rule = b["RRULE"]?.firstOrNull()?.let { parseRule(it.value, start.tz) }
            if (rule == null) {
                if (start.millis < to && start.millis + dur >= from) out += Event(title, start.millis, start.millis + dur, start.allDay, color, calName)
                continue
            }
            val ex = HashSet<Long>()
            b["EXDATE"]?.forEach { p -> p.value.split(',').forEach { v -> parseDt(Prop(p.params, v.trim()))?.let { ex += it.millis } } }
            for (t in expand(start, rule, from - dur, to)) {
                if (t in ex || (uid != null && "$uid|$t" in replaced)) continue
                val end = if (start.allDay) Calendar.getInstance().apply { timeInMillis = t; add(Calendar.DAY_OF_MONTH, (dur / DAY.toDouble()).roundToLong().toInt().coerceAtLeast(1)) }.timeInMillis else t + dur
                out += Event(title, t, end, start.allDay, color, calName)
            }
        }
        return out
    }

    private fun parseDt(p: Prop): Parsed? {
        val v = p.value.trim()
        return try {
            if (p.params["VALUE"] == "DATE" || v.length == 8) {
                val tz = TimeZone.getDefault()
                val cal = Calendar.getInstance(tz).apply { clear(); set(v.substring(0, 4).toInt(), v.substring(4, 6).toInt() - 1, v.substring(6, 8).toInt()) }
                Parsed(cal.timeInMillis, true, tz)
            } else {
                val utc = v.endsWith("Z")
                val tz = when {
                    utc -> TimeZone.getTimeZone("UTC")
                    p.params["TZID"] != null && TimeZone.getAvailableIDs().contains(p.params["TZID"]) -> TimeZone.getTimeZone(p.params["TZID"])
                    else -> TimeZone.getDefault()
                }
                val f = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = tz; isLenient = false }
                Parsed(f.parse(v.removeSuffix("Z").take(15))!!.time, false, tz)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDuration(v: String): Long? {
        val m = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").matchEntire(v.trim()) ?: return null
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        val ms = ((g(2) * 7 + g(3)) * 24 * 3600 + g(4) * 3600 + g(5) * 60 + g(6)) * 1000
        return if (m.groupValues[1] == "-") -ms else ms
    }

    private fun parseRule(v: String, tz: TimeZone): Rule? {
        val kv = v.split(';').mapNotNull { s -> s.indexOf('=').takeIf { it > 0 }?.let { s.substring(0, it).uppercase() to s.substring(it + 1) } }.toMap()
        val freq = kv["FREQ"]?.uppercase() ?: return null
        if (freq !in listOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null
        val days = mapOf("SU" to Calendar.SUNDAY, "MO" to Calendar.MONDAY, "TU" to Calendar.TUESDAY, "WE" to Calendar.WEDNESDAY, "TH" to Calendar.THURSDAY, "FR" to Calendar.FRIDAY, "SA" to Calendar.SATURDAY)
        val until = kv["UNTIL"]?.let { u -> parseDt(Prop(if (u.endsWith("Z")) emptyMap() else mapOf("TZID" to tz.id), u))?.millis?.let { if (u.length == 8) it + DAY - 1 else it } }
        return Rule(
            freq, kv["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1, kv["COUNT"]?.toIntOrNull(), until,
            kv["BYDAY"]?.split(',')?.mapNotNull { days[it.takeLast(2).uppercase()] } ?: emptyList(),
        )
    }

    /** Start times of the occurrences, wall-clock time kept across daylight saving; those that end before [from] are left out. */
    private fun expand(first: Parsed, r: Rule, from: Long, to: Long): List<Long> {
        val out = ArrayList<Long>()
        val base = Calendar.getInstance(first.tz).apply { timeInMillis = first.millis; firstDayOfWeek = Calendar.MONDAY }
        var n = 0
        fun add(t: Long): Boolean { // false: the rule is finished
            if (t < first.millis) return true
            if (r.until != null && t > r.until) return false
            if (r.count != null && n >= r.count) return false
            if (t >= to) return false
            n++
            if (t >= from) out += t
            return true
        }
        steps@ for (i in 0 until 6000) {
            val k = i * r.interval
            val c = base.clone() as Calendar
            when (r.freq) {
                "DAILY" -> { c.add(Calendar.DAY_OF_MONTH, k); if (!add(c.timeInMillis)) break@steps }
                "WEEKLY" -> {
                    c.add(Calendar.WEEK_OF_YEAR, k)
                    val wanted = if (r.byDay.isEmpty()) listOf(base.get(Calendar.DAY_OF_WEEK)) else r.byDay
                    val times = wanted.map { dow -> (c.clone() as Calendar).apply { set(Calendar.DAY_OF_WEEK, dow) }.timeInMillis }.sorted()
                    for (t in times) if (!add(t)) break@steps
                }
                "MONTHLY" -> {
                    c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, k)
                    val day = base.get(Calendar.DAY_OF_MONTH)
                    if (day <= c.getActualMaximum(Calendar.DAY_OF_MONTH)) { c.set(Calendar.DAY_OF_MONTH, day); if (!add(c.timeInMillis)) break@steps }
                    else if (c.timeInMillis > to) break@steps
                }
                else -> {
                    c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.YEAR, k)
                    val day = base.get(Calendar.DAY_OF_MONTH)
                    if (day <= c.getActualMaximum(Calendar.DAY_OF_MONTH)) { c.set(Calendar.DAY_OF_MONTH, day); if (!add(c.timeInMillis)) break@steps }
                    else if (c.timeInMillis > to) break@steps
                }
            }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()
}
