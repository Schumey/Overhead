package de.schumey.overhead

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Top-down radar: you in the middle, every aircraft in range as a dot with a trail behind it, the nearest ones named. */
object RadarView {
    private const val BG = 0xFF22211D.toInt()
    private const val GREEN = 0xFF2E6F4E.toInt()
    private const val GREEN_TEXT = 0xFF4A9A70.toInt()
    private const val MAX_LABELS = 8

    fun render(ctx: Context, snap: Snapshot?, wPx: Int, hPx: Int, now: Long, loading: Boolean = false): Rendered {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * d
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(Path().apply { addRoundRect(0f, 0f, w, h, dp(26f), dp(26f), Path.Direction.CW) })
        c.drawColor(BG)

        val pad = dp(18f)
        val flights = if (snap?.error == Snapshot.ERR_LOCATION) emptyList() else snap?.flights.orEmpty()

        // header
        val hp = Poster.textPaint(Poster.mono, dp(9.5f)).apply { letterSpacing = 0.16f }
        val place = snap?.place?.uppercase(Locale.getDefault())
        val left = if (place.isNullOrEmpty()) ctx.getString(R.string.radar_title_unknown) else ctx.getString(R.string.radar_title, place)
        val right = Poster.liveText(ctx, snap, loading, now)
        val base = dp(24f)
        val leftFit = Poster.fitLine(hp, left, w - pad * 2 - hp.measureText(right) - dp(14f), hp.textSize, dp(7f))
        c.drawText(leftFit, pad, base, hp)
        c.drawText(right, w - pad - hp.measureText(right), base, hp) // same size as the left text, which may have shrunk

        // plot area
        val footerH = dp(34f)
        val top = dp(36f)
        val bottom = h - footerH
        val cx = w / 2
        val cy = (top + bottom) / 2
        val r = min(w / 2 - dp(14f), (bottom - top) / 2 - dp(2f))
        val radiusKm = (snap?.radiusKm ?: 25).coerceAtLeast(1)

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Poster.CREAM }
        line.alpha = 70; line.strokeWidth = dp(1f)
        c.drawLine(cx, cy - r - dp(4f), cx, cy + r + dp(4f), line)
        c.drawLine(cx - r - dp(4f), cy, cx + r + dp(4f), cy, line)
        line.alpha = 100
        val innerKm = niceInner(radiusKm)
        c.drawCircle(cx, cy, r * innerKm / radiusKm, line)
        line.alpha = 190; line.strokeWidth = dp(1.6f)
        c.drawCircle(cx, cy, r, line)
        val ringLabel = Poster.textPaint(Poster.mono, dp(9f), 0xFFC9C6BB.toInt())
        c.drawText("$radiusKm km", cx + dp(6f), cy - r + dp(12f), ringLabel)
        c.drawText("$innerKm km", cx + dp(6f), cy - r * innerKm / radiusKm + dp(12f), ringLabel)

        // aircraft: far ones first so the near ones end up on top
        val ordered = flights.sortedByDescending { it.distanceKm }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Poster.CREAM; strokeWidth = dp(1.8f) }
        val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; strokeWidth = dp(2.6f); strokeCap = Paint.Cap.ROUND }
        val pos = HashMap<String, Pair<Float, Float>>()
        for (f in ordered) {
            val dist = (f.distanceKm / radiusKm).coerceAtMost(1.0).toFloat() * r
            val a = Math.toRadians(f.bearing)
            val x = cx + dist * sin(a).toFloat()
            val y = cy - dist * cos(a).toFloat()
            pos[f.hex] = x to y
            f.track?.let { t ->
                val ta = Math.toRadians(t)
                val len = dp(9f) + dp(10f) * ((f.speedKmh ?: 500) / 900f).coerceIn(0.2f, 1f)
                // trail behind the aircraft, so the dot is the head of its path
                c.drawLine(x, y, x - len * sin(ta).toFloat(), y + len * cos(ta).toFloat(), trail)
            }
            c.drawCircle(x, y, dp(5.5f), dot)
            c.drawCircle(x, y, dp(5.5f), ring)
        }

        // you
        house(c, cx, cy, dp(7f))

        // Labels for the nearest aircraft. Dots and the house count as obstacles; each label tries the right side, the
        // left side and above/below its dot, and is dropped if none is free (a crowded middle stays readable).
        val dotR = dp(8f)
        val placed = ArrayList<RectF>()
        placed += RectF(cx - dp(9f), cy - dp(9f), cx + dp(9f), cy + dp(9f)) // the house
        val dotRects = pos.values.map { (px, py) -> RectF(px - dotR, py - dotR, px + dotR, py + dotR) }
        val name = Poster.textPaint(Poster.heavy, dp(11f)).apply { textScaleX = 1.04f }
        val alt = Poster.textPaint(Poster.mono, dp(8.5f), 0xFFD8D5CA.toInt())
        var labelled = 0
        for (f in flights.sortedBy { it.distanceKm }) {
            if (labelled >= MAX_LABELS) break
            val (x, y) = pos[f.hex] ?: continue
            val title = Fmt.flightNo(f)
            val altText = f.altitudeM?.let { "${Fmt.thousands(it)} m" } ?: ""
            val tw = maxOf(name.measureText(title), alt.measureText(altText))
            val th = if (altText.isEmpty()) dp(14f) else dp(24f)
            // candidate top-left corners of the label box
            val candidates = listOf(
                x + dp(9f) to y - dp(11f), // right
                x - dp(9f) - tw to y - dp(11f), // left
                x - tw / 2 to y - dp(12f) - th, // above
                x - tw / 2 to y + dp(10f), // below
            )
            val own = RectF(x - dotR, y - dotR, x + dotR, y + dotR)
            val chosen = candidates.firstNotNullOfOrNull { (bx, by) ->
                val r = RectF(bx - dp(2f), by, bx + tw + dp(2f), by + th)
                val free = r.left >= dp(2f) && r.right <= w - dp(2f) && r.top >= dp(34f) && r.bottom <= h - footerH + dp(6f) &&
                    placed.none { RectF.intersects(it, r) } && dotRects.none { d2 -> d2 != own && RectF.intersects(d2, r) }
                if (free) Triple(bx, by, r) else null
            } ?: continue
            placed += chosen.third
            labelled++
            c.drawText(title, chosen.first, chosen.second + dp(14.5f), name)
            if (altText.isNotEmpty()) c.drawText(altText, chosen.first, chosen.second + dp(24f), alt)
        }

        // footer
        val fp = Poster.textPaint(Poster.mono, dp(9.5f))
        val nearest = flights.minByOrNull { it.distanceKm }
        val summary = when {
            loading || snap == null -> ctx.getString(R.string.loading)
            snap.error == Snapshot.ERR_LOCATION -> ctx.getString(R.string.err_location)
            nearest == null -> ctx.getString(R.string.radar_none)
            else -> ctx.getString(R.string.radar_summary, flights.size, Fmt.flightNo(nearest), Fmt.distance(nearest.distanceKm))
        }
        val north = "N ↑"
        val np = Poster.textPaint(Poster.monoBold, dp(11f), GREEN_TEXT)
        c.drawText(north, w - pad - np.measureText(north), h - dp(14f), np)
        c.drawText(Poster.fitLine(fp, summary, w - pad * 2 - np.measureText(north) - dp(12f), fp.textSize, dp(7.5f)), pad, h - dp(14f), fp)

        val desc = summary
        return Rendered(bmp, hPx, emptyList(), emptyList(), desc)
    }

    /** A round inner ring at about 40 % of the outer radius: 10 for 25 km, 20 for 50 km, 40 for 100 km. */
    internal fun niceInner(radiusKm: Int): Int = (radiusKm * 0.4).roundToInt().coerceAtLeast(1)

    private fun house(c: Canvas, cx: Float, cy: Float, s: Float) {
        val p = Path().apply {
            moveTo(cx, cy - s); lineTo(cx + s, cy - s * 0.1f); lineTo(cx + s * 0.62f, cy - s * 0.1f)
            lineTo(cx + s * 0.62f, cy + s * 0.8f); lineTo(cx - s * 0.62f, cy + s * 0.8f)
            lineTo(cx - s * 0.62f, cy - s * 0.1f); lineTo(cx - s, cy - s * 0.1f); close()
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Poster.CREAM })
    }
}
