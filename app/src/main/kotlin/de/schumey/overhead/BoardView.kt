package de.schumey.overhead

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale
import kotlin.math.roundToInt

/** Departure-board style table of the aircraft overhead, nearest first: flight, type, from, to, altitude, distance. */
object BoardView {
    private const val BG = 0xFF24231F.toInt()
    private const val ROW_BG = 0xFF2E2D28.toInt()
    private const val GOLD = 0xFFE8B02F.toInt()
    private const val COLUMN_HEAD = 0xFF8E8B82.toInt()
    const val MAX_ROWS = 10

    fun render(ctx: Context, snap: Snapshot?, wPx: Int, hPx: Int, now: Long, loading: Boolean = false): Rendered {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * d
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(Path().apply { addRoundRect(0f, 0f, w, h, dp(26f), dp(26f), Path.Direction.CW) })
        c.drawColor(BG)

        val pad = dp(16f)
        val flights = (if (snap?.error == Snapshot.ERR_LOCATION) emptyList() else snap?.flights.orEmpty()).sortedBy { it.distanceKm }

        // header: title left, place and time right, gold rule below
        val title = Poster.textPaint(Poster.heavy, dp(21f), GOLD).apply { textScaleX = 1.1f; letterSpacing = 0.04f }
        c.drawText(ctx.getString(R.string.board_title), pad, dp(34f), title)
        val place = snap?.place?.uppercase(Locale.getDefault())
        val live = if (loading) ctx.getString(R.string.poster_loading) else snap?.let { Fmt.time(it.time) }.orEmpty()
        val rightText = if (place.isNullOrEmpty()) live else "$place · $live"
        val rp = Poster.textPaint(Poster.mono, dp(9f)).apply { letterSpacing = 0.12f }
        val rightFit = Poster.fitLine(rp, rightText, w - pad * 2 - title.measureText(ctx.getString(R.string.board_title)) - dp(12f), rp.textSize, dp(7f))
        c.drawText(rightFit, w - pad - rp.measureText(rightFit), dp(33f), rp)
        c.drawRect(pad, dp(44f), w - pad, dp(46f), Paint().apply { color = GOLD })

        // columns
        val cw = w - pad * 2
        val xFlight = pad + dp(8f)
        val xType = pad + cw * 0.27f
        val xFrom = pad + cw * 0.43f
        val xTo = pad + cw * 0.58f
        val xAlt = pad + cw * 0.855f
        val xKm = w - pad - dp(8f)
        val headP = Poster.textPaint(Poster.mono, dp(7.5f), COLUMN_HEAD).apply { letterSpacing = 0.12f }
        val headY = dp(66f)
        c.drawText(ctx.getString(R.string.board_col_flight), xFlight, headY, headP)
        c.drawText(ctx.getString(R.string.board_col_type), xType, headY, headP)
        c.drawText(ctx.getString(R.string.board_col_from), xFrom, headY, headP)
        c.drawText(ctx.getString(R.string.board_col_to), xTo, headY, headP)
        rightAligned(c, ctx.getString(R.string.board_col_alt), xAlt, headY, headP)
        rightAligned(c, ctx.getString(R.string.board_col_km), xKm, headY, headP)

        // rows
        val rowH = dp(24f)
        val gap = dp(3f)
        val firstTop = dp(73f)
        val fit = ((h - firstTop - dp(10f)) / (rowH + gap)).toInt().coerceIn(0, MAX_ROWS)
        val shown = flights.take(fit)
        val rowsOut = ArrayList<IntArray>()
        val flightP = Poster.textPaint(Poster.monoBold, dp(11.5f), GOLD)
        val cellP = Poster.textPaint(Poster.mono, dp(10.5f))
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ROW_BG }
        shown.forEachIndexed { i, f ->
            val top = firstTop + i * (rowH + gap)
            c.drawRoundRect(RectF(pad, top, w - pad, top + rowH), dp(4f), dp(4f), bg)
            val y = top + rowH / 2 + dp(4f)
            c.drawText(Poster.fitLine(flightP, Fmt.flightNo(f), xType - xFlight - dp(4f), flightP.textSize, dp(8f)), xFlight, y, flightP)
            c.drawText(f.type ?: "–", xType, y, cellP)
            c.drawText(f.route?.origin?.iata ?: "–", xFrom, y, cellP)
            c.drawText(f.route?.destination?.iata ?: "–", xTo, y, cellP)
            rightAligned(c, f.altitudeM?.let { Fmt.thousands(it) } ?: "–", xAlt, y, cellP)
            rightAligned(c, Fmt.km1(f.distanceKm), xKm, y, cellP)
            rowsOut += intArrayOf(top.roundToInt(), (top + rowH).roundToInt())
        }

        if (shown.isEmpty()) {
            val msg = when {
                loading || snap == null -> ctx.getString(R.string.loading)
                snap.error == Snapshot.ERR_LOCATION -> ctx.getString(R.string.err_location)
                snap.error == Snapshot.ERR_NETWORK && snap.flights.isEmpty() -> ctx.getString(R.string.err_network)
                else -> ctx.getString(R.string.empty, snap.radiusKm)
            }
            val mp = Poster.textPaint(Poster.mono, dp(11f), 0xFFC9C6BB.toInt())
            c.drawText(Poster.fitLine(mp, msg, cw, mp.textSize, dp(8f)), pad + dp(4f), firstTop + dp(24f), mp)
        }

        val desc = shown.joinToString("; ") { "${Fmt.flightNo(it)} ${it.type.orEmpty()} ${it.route?.origin?.iata ?: "?"}→${it.route?.destination?.iata ?: "?"}" }
        return Rendered(bmp, rowsOut.firstOrNull()?.get(0) ?: hPx, rowsOut, shown.map { it.hex }, desc)
    }

    private fun rightAligned(c: Canvas, text: String, right: Float, y: Float, p: Paint) =
        c.drawText(text, right - p.measureText(text), y, p)
}
