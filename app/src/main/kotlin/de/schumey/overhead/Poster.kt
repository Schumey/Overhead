package de.schumey.overhead

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.Locale
import java.util.Random
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The widget as a poster: a sky strip (time of day, weather and moon phase of the real sky) with the nearest aircraft
 * drawn in airline colours, and below it the route, airline, type and altitude as big type, plus a compass that
 * points at the nearest aircraft. Rendered into one bitmap because RemoteViews cannot draw this.
 */
object Poster {
    // Shared with the other views (radar, board, runway) so all of them speak the same visual language.
    internal const val BG = 0xFF21201C.toInt()
    internal const val CREAM = 0xFFF1EEE3.toInt()
    internal const val MUTED = 0xFF9A978D.toInt()
    internal const val RED = 0xFFD3162F.toInt()

    internal val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    internal val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    internal val heavy: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    private const val MAX_ROWS = 3

    /** [sky] false: only the text blocks, from the top (the dashboard draws a sky shared with the weather above them). */
    fun render(ctx: Context, snap: Snapshot?, wPx: Int, hPx: Int, now: Long, loading: Boolean = false, sky: Boolean = true): Rendered {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * d
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (sky) c.clipPath(Path().apply { addRoundRect(0f, 0f, w, h, dp(26f), dp(26f), Path.Direction.CW) })
        c.drawColor(BG)

        val pad = dp(16f)
        val headerH = dp(30f)
        val flights = if (snap?.error == Snapshot.ERR_LOCATION) emptyList() else snap?.flights.orEmpty()
        val textX = pad + dp(16f)

        // ---- row texts and the vertical budget ----
        val heroRight = dp(52f) // room for the compass
        val heroMaxW = w - textX - pad - heroRight
        val rowMaxW = w - textX - pad
        val detailPaint = textPaint(mono, dp(10.5f))
        val heroText = flights.firstOrNull()?.let { rowText(ctx, it, now, hero = true) }
        val heroLines = heroText?.let { detailLines(detailPaint, it.tokens, heroMaxW, dp(10.5f), dp(9f)) }.orEmpty()
        val heroBlockH = dp(6f) + dp(23f) + dp(5f) + heroLines.size * dp(15f) + dp(7f)
        val secondaryBlockH = dp(6f) + dp(15f) + dp(4f) + dp(14f) + dp(7f)
        val sepH = dp(8f)

        var shown = 0
        var blocksH = 0f
        if (flights.isNotEmpty()) {
            shown = 1
            blocksH = heroBlockH
            val minSky = if (sky) max(dp(86f), h * 0.34f) else 0f
            while (shown < min(MAX_ROWS, flights.size) && h - (blocksH + secondaryBlockH + sepH) >= minSky) {
                blocksH += secondaryBlockH
                shown++
            }
        }
        val skyBottom = if (!sky) (if (shown == 0) h else 0f) else if (shown == 0) h else h - blocksH - sepH

        // ---- sky ----
        val scene = flights.take(shown)
        if (sky) {
            SkyArt.draw(c, snap?.lat, snap?.lon, now, SkyArt.Conditions(cloud = (snap?.cloudCover ?: 40) / 100f, precipMm = snap?.precipMm ?: 0.0), w, skyBottom, d, headerH)
            drawPlanes(c, scene, w, skyBottom, d, headerH)
        }
        if (shown == 0) drawMessage(c, ctx, snap, loading, w, skyBottom, pad, d)

        // ---- header ----
        if (sky) drawHeader(c, ctx, snap, loading, now, w, pad, headerH, d)

        // ---- text rows ----
        val rows = ArrayList<IntArray>()
        if (shown > 0) {
            val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CREAM; strokeWidth = dp(2f) }
            c.drawLine(pad, skyBottom + dp(4f), w - pad, skyBottom + dp(4f), lineP)
            var y = skyBottom + sepH
            for (i in 0 until shown) {
                val f = flights[i]
                val hero = i == 0
                val text = if (hero) heroText!! else rowText(ctx, f, now, hero = false)
                val top = y
                val maxW = if (hero) heroMaxW else rowMaxW
                val titleP = textPaint(heavy, if (hero) dp(22f) else dp(14.5f), CREAM).apply { textScaleX = 1.06f }
                val title = fitLine(titleP, text.title, maxW, titleP.textSize, titleP.textSize * 0.6f)
                val titleBase = y + dp(6f) + (if (hero) dp(21f) else dp(14f))
                if (i > 0) {
                    val div = Paint().apply { color = MUTED; alpha = 110; strokeWidth = dp(1f) }
                    c.drawLine(textX, top + dp(1f), w - pad, top + dp(1f), div)
                }
                val idxP = textPaint(monoBold, dp(10f), RED)
                c.drawText("${i + 1}", pad, titleBase - titleP.textSize * 0.32f, idxP)
                c.drawText(title, textX, titleBase, titleP)

                var lineY = titleBase + (if (hero) dp(18f) else dp(14.5f))
                val lines = if (hero) heroLines else listOf(secondaryLine(text.tokens, rowMaxW, dp(9.5f), dp(8f)))
                val startSize = if (hero) dp(10.5f) else dp(9.5f)
                // All lines of a row share the smallest size any of them needed, so they read as one block.
                val size = lines.minOf { ln -> textPaint(mono, startSize).also { fitLine(it, ln, maxW, startSize, dp(8f)) }.textSize }
                for (ln in lines) {
                    val p = textPaint(mono, size, if (hero) CREAM else 0xFFD8D5CA.toInt())
                    c.drawText(fitLine(p, ln, maxW, size, dp(8f)), textX, lineY, p)
                    lineY += dp(15f)
                }
                y += if (hero) heroBlockH else secondaryBlockH
                rows += intArrayOf(top.roundToInt(), y.roundToInt())
                if (hero) drawCompass(c, w - pad - dp(20f), top + heroBlockH / 2 + dp(5f), dp(17f), f.bearing, d)
            }
        }

        val desc = scene.joinToString("; ") { f ->
            val t = rowText(ctx, f, now, hero = false)
            t.title + ", " + t.tokens.joinToString(", ") { it.text }
        }
        return Rendered(bmp, skyBottom.roundToInt(), rows, scene.map { it.hex }, desc)
    }

    // ------------------------------------------------------------------ text

    private class Tok(val text: String, val drop: Int)
    private class RowText(val title: String, val tokens: List<Tok>)

    private fun rowText(ctx: Context, f: Flight, now: Long, hero: Boolean): RowText {
        val r = f.route
        val loc = Locale.getDefault()
        fun ap(a: Airport?): String = when {
            a == null -> "?"
            hero -> a.city.ifEmpty { a.iata }
            else -> "${a.city} ${a.iata}".trim()
        }
        val hasRoute = r != null && (r.origin != null || r.destination != null)
        val title = (if (hasRoute) "${ap(r!!.origin)} → ${ap(r.destination)}" else f.callsign).uppercase(loc)
        val type = TypeNames.name(f.type) ?: f.type
        val alt = f.altitudeM?.let { String.format(Locale.US, "%,d m", it).replace(',', '\'') }
        val speed = f.speedKmh?.let { "$it km/h" }
        val eta = f.etaMillis?.let { ctx.getString(R.string.eta_token, Fmt.time(it)) }
        val tokens = buildList {
            r?.airline?.let { add(Tok(it.uppercase(loc), 4)) }
            add(Tok(r?.flightNo ?: f.callsign, 9))
            type?.let { add(Tok(it, 2)) }
            if (!hasRoute) f.registration?.let { add(Tok(it, 3)) }
            alt?.let { add(Tok(it, 3)) }
            speed?.let { add(Tok(it, 1)) }
            eta?.let { add(Tok(it, 5)) }
        }
        return RowText(title, tokens)
    }

    /** One line if it fits at a readable size, otherwise two (identity and airline first, numbers second). */
    private fun detailLines(p: TextPaint, tokens: List<Tok>, maxW: Float, size: Float, minSize: Float): List<String> {
        val joined = tokens.joinToString(" · ") { it.text }
        p.textSize = minSize
        if (p.measureText(joined) <= maxW) return listOf(joined)
        val split = min(3, tokens.size - 1).coerceAtLeast(1)
        if (tokens.size < 2) return listOf(joined)
        return listOf(
            tokens.take(split).joinToString(" · ") { it.text },
            tokens.drop(split).joinToString(" · ") { it.text },
        )
    }

    /** One line: least important facts are dropped until it fits at the smallest size. */
    private fun secondaryLine(tokens: List<Tok>, maxW: Float, size: Float, minSize: Float): String {
        val p = textPaint(mono, minSize)
        val keep = tokens.toMutableList()
        while (keep.size > 1 && p.measureText(keep.joinToString(" · ") { it.text }) > maxW) {
            keep.remove(keep.minByOrNull { it.drop })
        }
        return keep.joinToString(" · ") { it.text }
    }

    internal fun textPaint(tf: Typeface, size: Float, color: Int = CREAM) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf; textSize = size; this.color = color
    }

    /** "LIVE · 23:52" while the data is fresh, "STAND 23:40" when it is old or failed. */
    internal fun liveText(ctx: Context, snap: Snapshot?, loading: Boolean, now: Long): String = when {
        loading -> ctx.getString(R.string.poster_loading)
        snap == null -> ""
        snap.error == null && now - snap.time < 10 * 60_000 -> ctx.getString(R.string.poster_live, Fmt.time(snap.time))
        else -> ctx.getString(R.string.poster_stale, Fmt.time(snap.time))
    }

    /** Shrinks the paint's text size until [text] fits, then cuts with an ellipsis if even the smallest size is too wide. */
    internal fun fitLine(p: TextPaint, text: String, maxW: Float, size: Float, minSize: Float): String {
        p.textSize = size
        while (p.measureText(text) > maxW && p.textSize > minSize) p.textSize -= 0.5f
        if (p.measureText(text) <= maxW) return text
        var t = text
        while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    internal fun drawHeader(c: Canvas, ctx: Context, snap: Snapshot?, loading: Boolean, now: Long, w: Float, pad: Float, headerH: Float, d: Float) {
        val scrim = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, headerH * 1.4f, 0xA021201C.toInt(), 0x0021201C, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, headerH * 1.4f, scrim)

        val p = textPaint(mono, 9.5f * d).apply { letterSpacing = 0.16f }
        val place = snap?.place?.uppercase(Locale.getDefault())
        val left = if (place.isNullOrEmpty()) ctx.getString(R.string.poster_title_unknown) else ctx.getString(R.string.poster_title, place)
        val right = liveText(ctx, snap, loading, now)
        val base = 20f * d
        val rightW = p.measureText(right)
        val leftFit = fitLine(p, left, w - pad * 2 - rightW - 16f * d, p.textSize, 7f * d)
        c.drawText(leftFit, pad, base, p)
        c.drawText(right, w - pad - p.measureText(right), base, p) // same size as the left text, which may have shrunk
    }

    private fun drawMessage(c: Canvas, ctx: Context, snap: Snapshot?, loading: Boolean, w: Float, skyH: Float, pad: Float, d: Float) {
        val msg = when {
            loading || snap == null -> ctx.getString(R.string.loading)
            snap.error == Snapshot.ERR_LOCATION -> ctx.getString(R.string.err_location)
            snap.error == Snapshot.ERR_NETWORK && snap.flights.isEmpty() -> ctx.getString(R.string.err_network)
            else -> ctx.getString(R.string.empty, snap.radiusKm)
        }
        val tp = textPaint(mono, 12f * d)
        val layout = StaticLayout.Builder.obtain(msg, 0, msg.length, tp, (w - pad * 4).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        c.save()
        c.translate(pad * 2, (skyH - layout.height) / 2 + 8f * d)
        layout.draw(c)
        c.restore()
    }

    // ------------------------------------------------------------------ planes

    internal fun drawPlanes(c: Canvas, scene: List<Flight>, w: Float, skyH: Float, d: Float, headerH: Float) {
        if (scene.isEmpty()) return
        val labelH = 16f * d
        val pad = 10f * d
        val scales = floatArrayOf(1f, 0.66f, 0.54f)
        val usable = skyH - headerH * 0.6f
        val baseLen = min(0.46f * w, usable * 1.45f)
        // Lengths by size class, shrunk together if they would not fit side by side, so planes never overlap.
        var lens = scene.mapIndexed { i, f -> baseLen * scales[i] * PlaneArt.lengthFactor(f.type).coerceAtMost(1.18f) }
        val avail = w - 2 * pad
        val total = lens.sum()
        if (total > avail * 0.96f) lens = lens.map { it * avail * 0.96f / total }
        // Left to right: nearest, third, second (so the two largest are not neighbours); a single plane sits left of centre.
        val order = when (scene.size) { 1 -> listOf(0); 2 -> listOf(0, 1); else -> listOf(0, 2, 1) }
        val gap = if (scene.size > 1) ((avail - lens.sum()) / (scene.size - 1)).coerceAtLeast(0f) else 0f
        val centers = FloatArray(scene.size)
        var cursor = pad
        if (scene.size == 1) centers[0] = w * 0.36f
        else order.forEach { i -> centers[i] = cursor + lens[i] / 2; cursor += lens[i] + gap }
        scene.forEachIndexed { i, f ->
            val len = lens[i]
            val elev = Math.toDegrees(atan2((f.altitudeM ?: 0) / 1000.0, f.distanceKm.coerceAtLeast(0.3))).toFloat()
            val e = sqrt((elev / 90f).coerceIn(0f, 1f)).coerceIn(0.15f, 0.85f)
            val minY = headerH * 0.8f + len * 0.28f
            val maxY = skyH - labelH - len * 0.18f - 4f * d
            val cy = (maxY - (maxY - minY) * e).coerceIn(min(minY, maxY), max(minY, maxY))
            val cx = centers[i].coerceIn(len * 0.5f + 4f * d, w - len * 0.5f - 4f * d)
            val facingRight = (f.track ?: 90.0) in 0.0..180.0
            PlaneArt.draw(c, cx, cy, len, facingRight, Liveries.forFlight(f), f.type)

            val lp = textPaint(monoBold, 9f * d).apply { letterSpacing = 0.15f }
            val label = "${i + 1} · ${f.route?.flightNo ?: f.callsign}"
            val ly = min(cy + len * 0.2f + 13f * d, skyH - 5f * d)
            c.drawText(label, (cx - lp.measureText(label) / 2).coerceAtLeast(4f * d), ly, lp)
        }
    }

    private fun drawCompass(c: Canvas, cx: Float, cy: Float, r: Float, bearing: Double, d: Float) {
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = CREAM; strokeWidth = 2f * d }
        c.drawCircle(cx, cy, r, ring)
        val np = textPaint(mono, 8.5f * d).apply { textAlign = Paint.Align.CENTER }
        c.drawText("N", cx, cy - r - 5f * d, np)
        c.save()
        c.rotate(bearing.toFloat(), cx, cy)
        val arrow = Path().apply {
            moveTo(cx, cy - r * 0.84f); lineTo(cx - r * 0.3f, cy + r * 0.22f); lineTo(cx, cy + r * 0.02f); lineTo(cx + r * 0.3f, cy + r * 0.22f); close()
        }
        c.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED })
        c.restore()
        c.drawCircle(cx, cy, 2.4f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CREAM })
    }
}
