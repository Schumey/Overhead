package de.schumey.overhead

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** The large widget: [WeatherView] on top, the agenda of [CalendarView] below, in one rounded picture. */
object DayView {
    /** Height of the weather part in pixels. */
    fun topHeight(hPx: Int, d: Float): Float = min(max(hPx * 0.52f, 170f * d), 270f * d).coerceAtMost(hPx - 120f * d).coerceAtLeast(hPx * 0.3f)

    fun render(ctx: Context, weather: WeatherState?, cal: Calendars.Data?, wPx: Int, hPx: Int, now: Long, loading: Boolean): Rendered {
        val d = ctx.resources.displayMetrics.density
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(Path().apply { addRoundRect(0f, 0f, w, h, 26f * d, 26f * d, Path.Direction.CW) })
        c.drawColor(Poster.BG)

        val top = topHeight(hPx, d).toInt()
        val wv = WeatherView.render(ctx, weather, wPx, top, now, loading, roundBottom = false)
        c.drawBitmap(wv.bitmap, 0f, 0f, null)

        val pad = 16f * d
        val labelP = Poster.textPaint(Poster.mono, 9.5f * d).apply { letterSpacing = 0.16f }
        c.drawText(ctx.getString(R.string.cal_title), pad, top + 18f * d, labelP)
        c.drawRect(pad, top + 24f * d, w - pad, top + 26f * d, Paint().apply { color = Poster.CREAM })
        val area = RectF(pad, top + 32f * d, w - pad, h - 10f * d)
        CalendarView.drawAgenda(c, ctx, cal, area, now, d)
        return Rendered(bmp, hPx, emptyList(), emptyList(), wv.description + " · " + ctx.getString(R.string.cal_title))
    }
}

/** Made-up appointments for the debug dumps (the emulator has no calendar). */
object DemoCalendar {
    fun data(now: Long): Calendars.Data {
        val day0 = Calendars.startOfDay(now)
        fun at(dayOffset: Int, hour: Int, min: Int = 0) = day0 + dayOffset * 86_400_000L + hour * 3_600_000L + min * 60_000L
        val blue = 0xFF7FB2E5.toInt(); val gold = 0xFFE8B02F.toInt(); val green = 0xFF8BC48A.toInt(); val rose = 0xFFD98A5B.toInt()
        val e = listOf(
            Calendars.Event("Geburtstag Oma", at(0, 0), at(1, 0), true, rose, "Familie"),
            Calendars.Event("Stand-up mit dem Team", at(0, 9, 30), at(0, 10), false, blue, "Arbeit"),
            Calendars.Event("Zahnarzt", at(0, 15), at(0, 16), false, green, "Privat"),
            Calendars.Event("Abendessen mit Lena und Tom im Restaurant am Hafen", at(0, 19, 30), at(0, 22), false, gold, "TimeTree"),
            Calendars.Event("Flug LH 1234 nach München", at(1, 7, 5), at(1, 8, 10), false, blue, "Arbeit"),
            Calendars.Event("Wochenmarkt", at(2, 10), at(2, 12), false, green, "Privat"),
            Calendars.Event("Konzert", at(4, 20), at(4, 23), false, gold, "TimeTree"),
            Calendars.Event("Umzug Sophie", at(5, 9), at(5, 17), false, rose, "Familie"),
        ).filter { it.end > now }
        return Calendars.Data(e, needsPermission = false, hasSources = true)
    }
}
