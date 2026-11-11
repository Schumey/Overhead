package de.schumey.overhead

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Paper-coloured airfield view: the runway as a strip (with aircraft on it), then one card per aircraft on the field
 * with its state (standing, taxiing, takeoff roll, departed, landed) and a legend and summary line.
 */
object RunwayView {
    private const val PAPER = 0xFFEEEBDF.toInt()
    private const val INK = 0xFF22211D.toInt()
    private const val CARD = 0xFFF8F6EF.toInt()
    private const val STRIPE = 0xFFB9B6AB.toInt()
    private const val SOFT = 0xFF6A675E.toInt()

    private const val RED = 0xFFC8102E.toInt()
    private const val GREEN = 0xFF2E6F4E.toInt()
    private const val YELLOW = 0xFFE0A81F.toInt()
    private const val BLUE = 0xFF2B5AA7.toInt()

    private fun color(s: FieldStatus) = when (s) {
        FieldStatus.TAKEOFF -> RED
        FieldStatus.TAXI -> GREEN
        FieldStatus.DEPARTED -> YELLOW
        FieldStatus.LANDED -> BLUE
        FieldStatus.STAND -> INK
    }

    fun render(ctx: Context, snap: Snapshot?, wPx: Int, hPx: Int, now: Long, loading: Boolean = false): Rendered {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * d
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(Path().apply { addRoundRect(0f, 0f, w, h, dp(26f), dp(26f), Path.Direction.CW) })
        c.drawColor(PAPER)

        val pad = dp(16f)
        val airport = if (snap?.error == Snapshot.ERR_LOCATION) null else snap?.airport
        val field = if (airport != null && snap != null) Airfield.classify(airport, snap.airportTraffic) else emptyList()

        // header
        val hp = Poster.textPaint(Poster.mono, dp(9.5f), INK).apply { letterSpacing = 0.16f }
        val name = airport?.name?.uppercase(Locale.getDefault())
        val left = if (name.isNullOrEmpty()) ctx.getString(R.string.runway_title_unknown) else ctx.getString(R.string.runway_title, name)
        val right = Poster.liveText(ctx, snap, loading, now)
        val leftFit = Poster.fitLine(hp, left, w - pad * 2 - hp.measureText(right) - dp(14f), hp.textSize, dp(7f))
        c.drawText(leftFit, pad, dp(24f), hp)
        c.drawText(right, w - pad - hp.measureText(right), dp(24f), hp) // same size as the left text, which may have shrunk
        c.drawRect(pad, dp(32f), w - pad, dp(35f), Paint().apply { color = INK })

        if (airport == null) {
            val msg = when {
                loading || snap == null -> ctx.getString(R.string.loading)
                snap.error == Snapshot.ERR_LOCATION -> ctx.getString(R.string.err_location)
                else -> ctx.getString(R.string.runway_no_airport)
            }
            val mp = Poster.textPaint(Poster.mono, dp(11f), SOFT)
            c.drawText(Poster.fitLine(mp, msg, w - pad * 2, mp.textSize, dp(8f)), pad, dp(70f), mp)
            return Rendered(bmp, hPx, emptyList(), emptyList(), msg)
        }

        // runway strip
        val stripTop = dp(62f) // leaves room above for the callsigns of aircraft on the runway
        val stripH = dp(34f)
        val stripL = pad
        val stripR = w - pad
        c.drawRect(stripL, stripTop, stripR, stripTop + stripH, Paint().apply { color = INK })
        val bar = Paint().apply { color = STRIPE }
        for (i in 0 until 6) { // threshold stripes at both ends
            val y = stripTop + dp(4f) + i * dp(4.5f)
            c.drawRect(stripL + dp(8f), y, stripL + dp(22f), y + dp(2.2f), bar)
            c.drawRect(stripR - dp(22f), y, stripR - dp(8f), y + dp(2.2f), bar)
        }
        val dash = Paint().apply { color = STRIPE }
        var x = stripL + dp(40f)
        while (x + dp(14f) < stripR - dp(40f)) { // dashed centre line
            c.drawRect(x, stripTop + stripH / 2 - dp(1.1f), x + dp(14f), stripTop + stripH / 2 + dp(1.1f), dash)
            x += dp(28f)
        }

        val onRunway = field.filter { it.runway >= 0 }
        val lp = Poster.textPaint(Poster.mono, dp(9.5f), 0xFFE6E3D6.toInt()).apply { letterSpacing = 0.2f }
        if (onRunway.isEmpty()) {
            // clear: the label sits in the middle of the strip, as in the design
            val label = ctx.getString(R.string.runway_free)
            val lw = lp.measureText(label)
            c.drawRect((stripL + stripR) / 2 - lw / 2 - dp(8f), stripTop + dp(6f), (stripL + stripR) / 2 + lw / 2 + dp(8f), stripTop + stripH - dp(6f), Paint().apply { color = INK })
            c.drawText(label, (stripL + stripR) / 2 - lw / 2, stripTop + stripH / 2 + dp(3.5f), lp)
        }

        // aircraft on the runway, along its length (the runway nearest to the first one on it)
        val shownRunway = onRunway.firstOrNull()?.runway ?: -1
        val mark = Paint(Paint.ANTI_ALIAS_FLAG)
        val tag = Poster.textPaint(Poster.monoBold, dp(8f), INK)
        onRunway.filter { it.runway == shownRunway }.forEachIndexed { i, ff ->
            val px = stripL + dp(30f) + (stripR - stripL - dp(60f)) * ff.along.toFloat()
            mark.color = color(ff.status)
            c.drawRoundRect(RectF(px - dp(6f), stripTop + stripH / 2 - dp(5f), px + dp(6f), stripTop + stripH / 2 + dp(5f)), dp(2f), dp(2f), mark)
            val t = Fmt.flightNo(ff.flight)
            c.drawText(t, (px - tag.measureText(t) / 2).coerceIn(pad, w - pad - tag.measureText(t)), stripTop - dp(4f) - (i % 2) * dp(9f), tag)
        }
        val under = Poster.textPaint(Poster.mono, dp(8f), INK)
        // The runway's name only when something is on it; otherwise the taxiway label, as in the design.
        val rwRef = airport.runways.getOrNull(shownRunway)?.ref?.takeIf { it.isNotEmpty() }
        c.drawText(if (rwRef != null) ctx.getString(R.string.runway_ref, rwRef) else ctx.getString(R.string.runway_taxiway), stripL + dp(2f), stripTop + stripH + dp(12f), under)
        if (onRunway.isNotEmpty()) { // busy: say so under the strip, where it cannot hide the markers
            val busy = ctx.getString(R.string.runway_busy)
            val bp = Poster.textPaint(Poster.monoBold, dp(8f), RED).apply { letterSpacing = 0.15f }
            c.drawText(busy, stripR - bp.measureText(busy) - dp(2f), stripTop + stripH + dp(12f), bp)
        }

        // legend and summary at the bottom
        val legendY = h - dp(28f)
        val footY = h - dp(12f)
        val lg = Poster.textPaint(Poster.mono, dp(8.5f), INK)
        var lx = pad
        for ((st, textRes) in listOf(
            FieldStatus.TAKEOFF to R.string.legend_takeoff, FieldStatus.TAXI to R.string.legend_taxi,
            FieldStatus.DEPARTED to R.string.legend_departed, FieldStatus.LANDED to R.string.legend_landed,
        )) {
            c.drawRect(lx, legendY - dp(8f), lx + dp(9f), legendY + dp(1f), Paint().apply { color = color(st) })
            val t = ctx.getString(textRes)
            c.drawText(t, lx + dp(13f), legendY, lg)
            lx += dp(13f) + lg.measureText(t) + dp(10f)
        }
        val km = snap?.let { s -> if (s.lat != null && s.lon != null) Geo.distanceKm(s.lat, s.lon, airport.lat, airport.lon) else null }
        val summary = ctx.getString(
            R.string.runway_summary, airport.icao, km?.let { Fmt.distance(it) } ?: "?",
            Airfield.beforeTakeoff(airport, field), Airfield.justDeparted(field),
        )
        c.drawText(Poster.fitLine(lg, summary, w - pad * 2, lg.textSize, dp(6.5f)), pad, footY, lg)

        // one card per aircraft on the field
        val cardsTop = stripTop + stripH + dp(24f)
        val cardH = dp(38f)
        val cardGap = dp(7f)
        val fit = ((legendY - dp(16f) - cardsTop) / (cardH + cardGap)).toInt().coerceIn(0, 4)
        val rows = ArrayList<IntArray>()
        val hexes = ArrayList<String>()
        field.take(fit).forEachIndexed { i, ff ->
            val top = cardsTop + i * (cardH + cardGap)
            card(ctx, c, ff, pad, top, w - pad * 2, cardH, d)
            rows += intArrayOf(top.roundToInt(), (top + cardH).roundToInt())
            hexes += ff.flight.hex
        }
        if (field.isEmpty()) {
            val mp = Poster.textPaint(Poster.mono, dp(10f), SOFT)
            c.drawText(Poster.fitLine(mp, ctx.getString(R.string.runway_quiet), w - pad * 2, mp.textSize, dp(7.5f)), pad, cardsTop + dp(16f), mp)
        }

        val desc = ctx.getString(R.string.runway_title, airport.name) + ": " +
            field.take(fit).joinToString("; ") { "${Fmt.flightNo(it.flight)} ${statusText(ctx, it.status)}" }
        return Rendered(bmp, rows.firstOrNull()?.get(0) ?: hPx, rows, hexes, desc)
    }

    private fun statusText(ctx: Context, s: FieldStatus) = ctx.getString(
        when (s) {
            FieldStatus.STAND -> R.string.status_stand
            FieldStatus.TAXI -> R.string.status_taxi
            FieldStatus.TAKEOFF -> R.string.status_takeoff
            FieldStatus.LANDED -> R.string.status_landed
            FieldStatus.DEPARTED -> R.string.status_departed
        },
    )

    private fun card(ctx: Context, c: Canvas, ff: FieldFlight, left: Float, top: Float, width: Float, height: Float, d: Float) {
        fun dp(v: Float) = v * d
        val box = RectF(left, top, left + width, top + height)
        c.drawRect(box, Paint().apply { color = CARD })
        val tagW = dp(64f)
        c.drawRect(left, top, left + tagW, top + height, Paint().apply { color = color(ff.status) })
        val tp = Poster.textPaint(Poster.monoBold, dp(9f), 0xFFF8F6EF.toInt())
        val st = Poster.fitLine(tp, statusText(ctx, ff.status), tagW - dp(8f), tp.textSize, dp(6.5f))
        c.drawText(st, left + (tagW - tp.measureText(st)) / 2, top + height / 2 + dp(3.5f), tp)
        c.drawRect(box, Paint().apply { color = INK; style = Paint.Style.STROKE; strokeWidth = dp(1.8f) })

        val f = ff.flight
        val textX = left + tagW + dp(9f)
        val rightX = left + width - dp(8f)
        val where = ctx.getString(
            when {
                ff.status == FieldStatus.DEPARTED -> R.string.where_air
                ff.runway >= 0 -> R.string.where_runway
                ff.status == FieldStatus.STAND -> R.string.where_stand
                else -> R.string.where_taxiway
            },
        )
        val whereP = Poster.textPaint(Poster.mono, dp(8.5f), INK)
        val farP = Poster.textPaint(Poster.mono, dp(7.5f), SOFT)
        val far = ctx.getString(R.string.from_field, Fmt.km1(ff.km) + " km")
        val rightW = max(whereP.measureText(where), farP.measureText(far))
        c.drawText(where, rightX - whereP.measureText(where), top + dp(15f), whereP)
        c.drawText(far, rightX - farP.measureText(far), top + dp(28f), farP)

        val maxLeft = rightX - rightW - dp(8f) - textX
        val nameP = Poster.textPaint(Poster.heavy, dp(13f), INK)
        val no = Poster.fitLine(nameP, Fmt.flightNo(f), maxLeft, nameP.textSize, dp(9f))
        c.drawText(no, textX, top + dp(16f), nameP)
        val typeName = (TypeNames.name(f.type) ?: f.type)?.uppercase(Locale.getDefault())
        val typeP = Poster.textPaint(Poster.monoBold, dp(9f), INK)
        val after = textX + nameP.measureText(no) + dp(8f)
        if (typeName != null && after + typeP.measureText(typeName) < textX + maxLeft) c.drawText(typeName, after, top + dp(16f), typeP)

        val detail = listOfNotNull(f.callsign.takeIf { it != Fmt.flightNo(f) }, f.registration, f.type).joinToString(" · ")
        val dp2 = Poster.textPaint(Poster.mono, dp(8f), SOFT)
        c.drawText(Poster.fitLine(dp2, detail.ifEmpty { f.callsign }, maxLeft, dp2.textSize, dp(6.5f)), textX, top + dp(29f), dp2)
    }
}
