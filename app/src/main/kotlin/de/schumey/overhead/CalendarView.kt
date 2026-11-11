package de.schumey.overhead

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Calendar look: the date as big type, then the coming appointments grouped by day, in the same dark poster style as
 * the flight and weather widgets. [drawAgenda] is shared with the combined weather + calendar widget.
 */
object CalendarView {
    private const val ROW_BG = 0xFF2E2D28.toInt()
    private const val GOLD = 0xFFE8B02F.toInt()
    private const val DAY_MS = 86_400_000L

    fun render(ctx: Context, data: Calendars.Data?, wPx: Int, hPx: Int, now: Long, loading: Boolean = false): Rendered {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * d
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(Path().apply { addRoundRect(0f, 0f, w, h, dp(26f), dp(26f), Path.Direction.CW) })
        c.drawColor(Poster.BG)
        c.drawRect(0f, 0f, w, dp(42f), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, dp(42f), 0x14F1EEE3, 0x00F1EEE3, Shader.TileMode.CLAMP)
        })

        val pad = dp(16f)
        drawHeader(c, ctx, loading, now, w, pad, d)
        var y = dp(30f)

        if (h >= dp(130f)) {
            y = drawDate(c, ctx, data, now, w, pad, y, d)
        }
        val area = RectF(pad, y, w - pad, h - dp(10f))
        drawAgenda(c, ctx, data, area, now, d)
        val next = data?.events?.firstOrNull()
        val desc = ctx.getString(R.string.widget_calendar_title) + (next?.let { " · ${it.title}" } ?: "")
        return Rendered(bmp, hPx, emptyList(), emptyList(), desc)
    }

    private fun drawHeader(c: Canvas, ctx: Context, loading: Boolean, now: Long, w: Float, pad: Float, d: Float) {
        val p = Poster.textPaint(Poster.mono, 9.5f * d).apply { letterSpacing = 0.16f }
        val right = if (loading) ctx.getString(R.string.poster_loading) else ctx.getString(R.string.poster_live, Fmt.time(now))
        c.drawText(ctx.getString(R.string.cal_title), pad, 20f * d, p)
        c.drawText(right, w - pad - p.measureText(right), 20f * d, p)
    }

    /** The big date block; returns the y below it. */
    private fun drawDate(c: Canvas, ctx: Context, data: Calendars.Data?, now: Long, w: Float, pad: Float, top: Float, d: Float): Float {
        fun dp(v: Float) = v * d
        val locale = Locale.getDefault()
        val dayP = Poster.textPaint(Poster.heavy, dp(44f)).apply { textScaleX = 1.05f }
        val day = SimpleDateFormat("d", locale).format(Date(now))
        c.drawText(day, pad, top + dp(40f), dayP)
        val tx = pad + dayP.measureText(day) + dp(14f)
        val maxW = w - tx - pad
        val wd = SimpleDateFormat("EEEE", locale).format(Date(now)).uppercase(locale)
        val wdP = Poster.textPaint(Poster.heavy, dp(15f)).apply { textScaleX = 1.05f }
        c.drawText(Poster.fitLine(wdP, wd, maxW, wdP.textSize, dp(10f)), tx, top + dp(16f), wdP)
        val mp = Poster.textPaint(Poster.mono, dp(9.5f))
        c.drawText(SimpleDateFormat("MMMM yyyy", locale).format(Date(now)).uppercase(locale), tx, top + dp(31f), mp)
        val endToday = Calendars.startOfDay(now) + DAY_MS
        val todayN = data?.events?.count { it.start < endToday } ?: 0
        val line = if (data == null || !data.hasSources) "" else if (todayN == 0) ctx.getString(R.string.cal_nothing_today)
        else ctx.resources.getQuantityString(R.plurals.cal_today_n, todayN, todayN)
        c.drawText(Poster.fitLine(Poster.textPaint(Poster.mono, dp(9.5f), GOLD), line, maxW, dp(9.5f), dp(7f)), tx, top + dp(44f), Poster.textPaint(Poster.mono, dp(9.5f), GOLD))
        c.drawRect(pad, top + dp(52f), w - pad, top + dp(54f), Paint().apply { color = Poster.CREAM })
        return top + dp(62f)
    }

    private class Item(val label: String?, val event: Calendars.Event?, val today: Boolean = false)

    /**
     * Draws the agenda into [area]: day labels and one row per appointment, as many as fit, then "+ n more".
     * Returns how many appointments were drawn (or -1 if a message was drawn instead).
     */
    fun drawAgenda(c: Canvas, ctx: Context, data: Calendars.Data?, area: RectF, now: Long, d: Float): Int {
        fun dp(v: Float) = v * d
        val msg = when {
            data == null -> ctx.getString(R.string.cal_loading)
            data.needsPermission -> ctx.getString(R.string.cal_permission)
            !data.hasSources -> ctx.getString(R.string.cal_no_sources)
            data.events.isEmpty() -> ctx.getString(R.string.cal_none, Calendars.WINDOW_DAYS)
            else -> null
        }
        if (msg != null) {
            val tp = Poster.textPaint(Poster.mono, 12f * d)
            val layout = android.text.StaticLayout.Builder.obtain(msg, 0, msg.length, tp, max(10, (area.width() - dp(24f)).toInt()))
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build()
            c.save()
            c.translate(area.left + dp(12f), area.top + max(0f, (area.height() - layout.height) / 2))
            layout.draw(c)
            c.restore()
            return -1
        }

        // flatten into day labels and rows
        val events = data!!.events
        val dayFmt = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEd MMM"), Locale.getDefault())
        val today0 = Calendars.startOfDay(now)
        val items = ArrayList<Item>()
        var lastDay = -1L
        for (e in events) {
            val dayStart = max(Calendars.startOfDay(e.start), today0) // an event that started earlier belongs to today
            if (dayStart != lastDay) {
                lastDay = dayStart
                val label = when {
                    dayStart == today0 -> ctx.getString(R.string.wx_today)
                    dayStart - today0 in DAY_MS / 2..(DAY_MS * 3 / 2) -> ctx.getString(R.string.cal_tomorrow)
                    else -> dayFmt.format(Date(dayStart + DAY_MS / 2)).uppercase(Locale.getDefault())
                }
                items += Item(label, null, dayStart == today0)
            }
            items += Item(null, e)
        }
        val labelH = dp(16f)
        val rowH = dp(24f)
        val gap = dp(3f)
        val moreH = dp(14f)
        fun hOf(i: Item) = if (i.event == null) labelH else rowH + gap
        // how many items fit; if not all, keep room for the "+ n more" line and do not end on a lone day label
        var n = 0
        var used = 0f
        for (i in items) { if (used + hOf(i) > area.height() + 0.5f) break; used += hOf(i); n++ }
        if (n < items.size) {
            n = 0; used = 0f
            for (i in items) { if (used + hOf(i) > area.height() - moreH) break; used += hOf(i); n++ }
            while (n > 0 && items[n - 1].event == null) n--
        }
        var y = area.top
        var drawn = 0
        for (k in 0 until n) {
            val it = items[k]
            if (it.event == null) {
                c.drawText(it.label!!, area.left + dp(2f), y + dp(11f), Poster.textPaint(Poster.monoBold, dp(9f), if (it.today) GOLD else Poster.MUTED).apply { letterSpacing = 0.08f })
                y += labelH
            } else {
                drawRow(c, ctx, it.event, RectF(area.left, y, area.right, y + rowH), now, d)
                y += rowH + gap
                drawn++
            }
        }
        if (drawn < events.size) {
            val more = ctx.getString(R.string.cal_more, events.size - drawn)
            c.drawText(more, area.left + dp(2f), y + dp(10f), Poster.textPaint(Poster.mono, dp(8.5f), Poster.MUTED))
        }
        return drawn
    }

    private fun drawRow(c: Canvas, ctx: Context, e: Calendars.Event, row: RectF, now: Long, d: Float) {
        c.drawRoundRect(row, 4f * d, 4f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ROW_BG })
        c.drawRoundRect(RectF(row.left, row.top + 4f * d, row.left + 3f * d, row.bottom - 4f * d), 1.5f * d, 1.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = e.color })
        val base = row.top + row.height() / 2 + 3.5f * d
        val running = !e.allDay && e.start <= now
        val tx = row.left + 10f * d
        val timeW = 44f * d
        val timeP = when {
            e.allDay -> Poster.textPaint(Poster.mono, 8f * d, Poster.MUTED)
            running -> Poster.textPaint(Poster.monoBold, 9.5f * d, Poster.RED)
            else -> Poster.textPaint(Poster.monoBold, 10f * d, GOLD)
        }
        val time = when {
            e.allDay -> ctx.getString(R.string.cal_all_day)
            running -> ctx.getString(R.string.cal_now)
            else -> Fmt.time(e.start)
        }
        c.drawText(Poster.fitLine(timeP, time, timeW - 4f * d, timeP.textSize, 6f * d), tx, base, timeP)
        val titleP = Poster.textPaint(Poster.monoBold, 10.5f * d)
        val room = row.right - 8f * d - (tx + timeW)
        c.drawText(Poster.fitLine(titleP, e.title, room, titleP.textSize, 8f * d).let { ellipsize(titleP, it, room) }, tx + timeW, base, titleP)
    }

    /** [fitLine] only shrinks the text; cut what still does not fit. */
    private fun ellipsize(p: Paint, s: String, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
