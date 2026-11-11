package de.schumey.overhead

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Weather poster: the real sky as the picture (sun, moon, clouds, rain, snow, fog, lightning), then the temperature as
 * big type with details and a wind compass, the next hours, and the coming days. Same visual language as the flight looks.
 */
object WeatherView {
    private const val ROW_BG = 0xFF2E2D28.toInt()
    private const val GOLD = 0xFFE8B02F.toInt()
    private const val BLUE = 0xFF7FB2E5.toInt()
    private const val MAX_DAYS = 5

    /** [roundBottom] false: the bottom corners are square, for a picture that continues below (the combined widget). */
    /** [sky] false: only the text blocks, from the top (the dashboard draws a sky shared with the flights above them). */
    fun render(ctx: Context, state: WeatherState?, wPx: Int, hPx: Int, now: Long, loading: Boolean = false, roundBottom: Boolean = true, sky: Boolean = true): Rendered {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * d
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = if (sky) dp(26f) else 0f
        val rb = if (roundBottom) r else 0f
        c.clipPath(Path().apply { addRoundRect(0f, 0f, w, h, floatArrayOf(r, r, r, r, rb, rb, rb, rb), Path.Direction.CW) })
        c.drawColor(Poster.BG)

        val pad = dp(16f)
        val headerH = dp(30f)
        val fc = if (state?.error == Snapshot.ERR_LOCATION) null else state?.forecast

        // ---- budget: the sky gets what the text blocks leave ----
        val mainH = dp(70f)
        val hourlyH = dp(58f)
        val rowH = dp(24f)
        val gap = dp(3f)
        val sepH = dp(8f)
        val minSky = if (sky) max(dp(84f), h * 0.30f) else 0f
        var blocks = 0f
        var hourlyShown = false
        var daysShown = 0
        if (fc != null) {
            blocks = mainH + sepH
            if (fc.hours.isNotEmpty() && h - (blocks + hourlyH + dp(6f)) >= minSky) {
                hourlyShown = true
                blocks += hourlyH + dp(6f)
            }
            while (daysShown < min(MAX_DAYS, fc.days.size) && h - (blocks + dp(12f) + (daysShown + 1) * (rowH + gap)) >= minSky) daysShown++
            if (daysShown > 0) blocks += dp(6f) + daysShown * (rowH + gap)
            blocks += dp(6f) // some air above the rounded bottom corners
        }
        val skyBottom = if (fc == null) h else if (!sky) 0f else h - blocks

        // ---- sky ----
        val kind = fc?.let { WeatherKind.of(it.code) }
        if (sky) {
            SkyArt.draw(c, state?.lat, state?.lon, now, conditions(state) ?: SkyArt.Conditions(), w, skyBottom, d, headerH, bodyScale = 1.25f)
            drawHeader(c, ctx, state, loading, now, w, pad, d)
        }
        if (fc == null || kind == null) {
            drawMessage(c, ctx, state, loading, w, skyBottom, pad, d)
            return Rendered(bmp, hPx, emptyList(), emptyList(), ctx.getString(R.string.widget_weather_title))
        }

        // ---- main block ----
        c.drawRect(pad, skyBottom + dp(4f), w - pad, skyBottom + dp(6f), Paint().apply { color = Poster.CREAM })
        var y = skyBottom + sepH
        val tempP = Poster.textPaint(Poster.heavy, dp(44f)).apply { textScaleX = 1.05f }
        val tempText = "${fc.tempC.roundToInt()}°"
        c.drawText(tempText, pad, y + dp(47f), tempP)
        val tx = pad + tempP.measureText(tempText) + dp(14f)
        val compassSpace = dp(50f)
        val maxW = w - tx - pad - compassSpace
        val condP = Poster.textPaint(Poster.heavy, dp(15f)).apply { textScaleX = 1.05f }
        c.drawText(Poster.fitLine(condP, ctx.getString(kind.labelRes).uppercase(Locale.getDefault()), maxW, condP.textSize, dp(10f)), tx, y + dp(22f), condP)

        val today = fc.days.firstOrNull()
        val line2 = listOfNotNull(
            fc.feelsC?.let { ctx.getString(R.string.wx_feels, it.roundToInt()) },
            fc.humidity?.let { ctx.getString(R.string.wx_humidity, it) },
        ).joinToString(" · ")
        val line3 = listOfNotNull(
            fc.windKmh?.let { ctx.getString(R.string.wx_wind, ctx.resources.getStringArray(R.array.cardinals)[Geo.cardinalIndex((fc.windDir ?: 0).toDouble())], it.roundToInt()) },
            if (today?.sunrise != null && today.sunset != null) ctx.getString(R.string.wx_sun, Fmt.time(today.sunrise), Fmt.time(today.sunset)) else null,
        ).joinToString(" · ")
        val dp1 = Poster.textPaint(Poster.mono, dp(9.5f))
        val fit2 = Poster.fitLine(dp1, line2, maxW, dp(9.5f), dp(7f)); val size2 = dp1.textSize
        c.drawText(fit2, tx, y + dp(39f), dp1)
        val dp3 = Poster.textPaint(Poster.mono, dp(9.5f), 0xFFD8D5CA.toInt())
        c.drawText(Poster.fitLine(dp3, line3, maxW, size2, dp(7f)), tx, y + dp(54f), dp3)
        fc.windDir?.let { compass(c, w - pad - dp(20f), y + dp(37f), dp(15f), it.toDouble(), d) }
        y += mainH

        // ---- next hours ----
        if (hourlyShown) {
            val box = RectF(pad, y + dp(6f), w - pad, y + dp(6f) + hourlyH)
            drawHourly(c, ctx, fc, box, now, d)
            y = box.bottom
        }

        // ---- coming days ----
        if (daysShown > 0) {
            y += dp(6f)
            val shown = fc.days.take(daysShown)
            val lo = fc.days.minOf { it.minC }
            val hi = fc.days.maxOf { it.maxC }
            shown.forEachIndexed { i, day ->
                drawDay(c, ctx, day, i == 0, RectF(pad, y, w - pad, y + rowH), lo, hi, d)
                y += rowH + gap
            }
        }

        val desc = "$tempText ${ctx.getString(kind.labelRes)}" + (state?.place?.let { " · $it" } ?: "")
        return Rendered(bmp, hPx, emptyList(), emptyList(), desc)
    }

    /** How the sky looks for this weather; null without a forecast. */
    fun conditions(state: WeatherState?): SkyArt.Conditions? {
        if (state?.error == Snapshot.ERR_LOCATION) return null
        val fc = state?.forecast ?: return null
        val kind = WeatherKind.of(fc.code)
        return SkyArt.Conditions(
            cloud = fc.cloud / 100f,
            // the current precipitation figure lags behind the weather code, so a wet code always shows at least some rain
            precipMm = if (kind.isWet && fc.precipMm < 0.3) 0.3 else fc.precipMm,
            snow = kind.isSnowy, fog = kind == WeatherKind.FOG, thunder = kind == WeatherKind.THUNDER,
        )
    }

    // ------------------------------------------------------------------ pieces

    private fun drawHeader(c: Canvas, ctx: Context, state: WeatherState?, loading: Boolean, now: Long, w: Float, pad: Float, d: Float) {
        c.drawRect(0f, 0f, w, 42f * d, Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, 42f * d, 0xA021201C.toInt(), 0x0021201C, android.graphics.Shader.TileMode.CLAMP)
        })
        val p = Poster.textPaint(Poster.mono, 9.5f * d).apply { letterSpacing = 0.16f }
        val place = state?.place?.uppercase(Locale.getDefault())
        val left = if (place.isNullOrEmpty()) ctx.getString(R.string.weather_title_unknown) else ctx.getString(R.string.weather_title, place)
        val right = when {
            loading -> ctx.getString(R.string.poster_loading)
            state == null || state.fetchedAt == 0L -> ""
            state.error == null && now - state.fetchedAt < 30 * 60_000 -> ctx.getString(R.string.poster_live, Fmt.time(state.fetchedAt))
            else -> ctx.getString(R.string.poster_stale, Fmt.time(state.fetchedAt))
        }
        val fitted = Poster.fitLine(p, left, w - pad * 2 - p.measureText(right) - 16f * d, p.textSize, 7f * d)
        c.drawText(fitted, pad, 20f * d, p)
        c.drawText(right, w - pad - p.measureText(right), 20f * d, p)
    }

    private fun drawMessage(c: Canvas, ctx: Context, state: WeatherState?, loading: Boolean, w: Float, skyH: Float, pad: Float, d: Float) {
        val msg = when {
            loading || state == null -> ctx.getString(R.string.loading_weather)
            state.error == Snapshot.ERR_LOCATION -> ctx.getString(R.string.err_location)
            else -> ctx.getString(R.string.err_network_weather)
        }
        val tp = Poster.textPaint(Poster.mono, 12f * d)
        val layout = android.text.StaticLayout.Builder.obtain(msg, 0, msg.length, tp, (w - pad * 4).toInt())
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build()
        c.save()
        c.translate(pad * 2, (skyH - layout.height) / 2 + 8f * d)
        layout.draw(c)
        c.restore()
    }

    private fun compass(c: Canvas, cx: Float, cy: Float, r: Float, fromDeg: Double, d: Float) {
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Poster.CREAM; strokeWidth = 2f * d })
        c.drawText("N", cx - 3f * d, cy - r - 4f * d, Poster.textPaint(Poster.mono, 8f * d))
        c.save()
        c.rotate(fromDeg.toFloat(), cx, cy) // the arrow points to where the wind comes from
        val arrow = Path().apply {
            moveTo(cx, cy - r * 0.84f); lineTo(cx - r * 0.3f, cy + r * 0.22f); lineTo(cx, cy + r * 0.02f); lineTo(cx + r * 0.3f, cy + r * 0.22f); close()
        }
        c.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Poster.RED })
        c.restore()
        c.drawCircle(cx, cy, 2.2f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Poster.CREAM })
    }

    private fun drawHourly(c: Canvas, ctx: Context, fc: Forecast, box: RectF, now: Long, d: Float) {
        c.drawRoundRect(box, 6f * d, 6f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ROW_BG })
        val cols = ((box.width() / (44f * d)).toInt()).coerceIn(3, 8)
        val upcoming = fc.hours.filter { it.time >= now - 30 * 60_000 }.take(cols)
        if (upcoming.isEmpty()) return
        val colW = box.width() / cols
        val hourFmt = SimpleDateFormat("HH", Locale.getDefault())
        val labelP = Poster.textPaint(Poster.mono, 8.5f * d, Poster.MUTED).apply { textAlign = Paint.Align.CENTER }
        val tempP = Poster.textPaint(Poster.monoBold, 10.5f * d).apply { textAlign = Paint.Align.CENTER }
        val probP = Poster.textPaint(Poster.mono, 7.5f * d, BLUE).apply { textAlign = Paint.Align.CENTER }
        upcoming.forEachIndexed { i, hp ->
            val cx = box.left + colW * (i + 0.5f)
            c.drawText(if (i == 0) ctx.getString(R.string.wx_now) else hourFmt.format(Date(hp.time)), cx, box.top + 13f * d, labelP)
            WeatherIcons.draw(c, WeatherKind.of(hp.code), hp.isDay, cx, box.top + 28f * d, 15f * d, ROW_BG)
            c.drawText("${hp.tempC.roundToInt()}°", cx, box.top + 44f * d, tempP)
            if ((hp.precipProb ?: 0) >= 20) c.drawText("${hp.precipProb}%", cx, box.top + 54f * d, probP)
        }
    }

    private fun drawDay(c: Canvas, ctx: Context, day: DayPoint, today: Boolean, row: RectF, weekLo: Double, weekHi: Double, d: Float) {
        c.drawRoundRect(row, 4f * d, 4f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ROW_BG })
        val cw = row.width()
        val base = row.top + row.height() / 2 + 3.5f * d
        val name = if (today) ctx.getString(R.string.wx_today) else SimpleDateFormat("EEE", Locale.getDefault()).format(Date(day.day + 12 * 3_600_000L)).uppercase(Locale.getDefault())
        c.drawText(name, row.left + 8f * d, base, Poster.textPaint(Poster.monoBold, 10f * d, GOLD))
        WeatherIcons.draw(c, WeatherKind.of(day.code), true, row.left + cw * 0.27f, row.centerY(), 14f * d, ROW_BG)
        if ((day.precipProb ?: 0) >= 20) c.drawText("${day.precipProb}%", row.left + cw * 0.34f, base, Poster.textPaint(Poster.mono, 8.5f * d, BLUE))

        val numP = Poster.textPaint(Poster.mono, 10f * d)
        val lowT = "${day.minC.roundToInt()}°"
        val highT = "${day.maxC.roundToInt()}°"
        val hiX = row.right - 8f * d - numP.measureText(highT)
        val loX = row.left + cw * 0.52f
        c.drawText(lowT, loX, base, numP)
        c.drawText(highT, hiX, base, numP.apply { color = Poster.CREAM; typeface = Poster.monoBold })
        // range bar between the two numbers, placed against the lowest and highest temperature of the whole week
        val b0 = loX + numP.measureText(lowT) + 6f * d
        val b1 = hiX - 6f * d
        if (b1 > b0 + 10f * d) {
            val bar = RectF(b0, row.centerY() - 2f * d, b1, row.centerY() + 2f * d)
            c.drawRoundRect(bar, 2f * d, 2f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F1E1B.toInt() })
            val span = max(0.1, weekHi - weekLo)
            val l = bar.left + (bar.width() * ((day.minC - weekLo) / span)).toFloat()
            val r = bar.left + (bar.width() * ((day.maxC - weekLo) / span)).toFloat()
            c.drawRoundRect(RectF(l, bar.top, max(r, l + 4f * d), bar.bottom), 2f * d, 2f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD })
        }
    }
}

/** Small flat weather symbols for the hour strip and the day rows. */
object WeatherIcons {
    private const val SUN = 0xFFF2C94C.toInt()
    private const val CLOUD = 0xFFC9C6BB.toInt()
    private const val CLOUD_DARK = 0xFF8F8C84.toInt()
    private const val RAIN = 0xFF7FB2E5.toInt()

    /** [size] is the width of the symbol; [behind] is the colour behind it (used to cut the moon's crescent). */
    fun draw(c: Canvas, kind: WeatherKind, day: Boolean, cx: Float, cy: Float, size: Float, behind: Int) {
        when (kind) {
            WeatherKind.CLEAR -> body(c, day, cx, cy, size * 0.36f, behind)
            WeatherKind.MOSTLY_CLEAR, WeatherKind.PARTLY -> {
                body(c, day, cx - size * 0.16f, cy - size * 0.14f, size * 0.26f, behind)
                cloud(c, cx + size * 0.1f, cy + size * 0.1f, size * 0.78f, CLOUD)
            }
            WeatherKind.OVERCAST -> cloud(c, cx, cy, size * 0.95f, CLOUD)
            WeatherKind.FOG -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CLOUD; strokeWidth = size * 0.1f; strokeCap = Paint.Cap.ROUND }
                c.drawLine(cx - size * 0.4f, cy - size * 0.2f, cx + size * 0.4f, cy - size * 0.2f, p)
                c.drawLine(cx - size * 0.3f, cy + size * 0.02f, cx + size * 0.5f, cy + size * 0.02f, p)
                c.drawLine(cx - size * 0.4f, cy + size * 0.24f, cx + size * 0.3f, cy + size * 0.24f, p)
            }
            WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.SHOWERS -> {
                cloud(c, cx, cy - size * 0.12f, size * 0.9f, CLOUD_DARK)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RAIN; strokeWidth = size * 0.08f; strokeCap = Paint.Cap.ROUND }
                for (i in -1..1) c.drawLine(cx + i * size * 0.24f + size * 0.05f, cy + size * 0.18f, cx + i * size * 0.24f - size * 0.04f, cy + size * 0.38f, p)
            }
            WeatherKind.SNOW, WeatherKind.SNOW_SHOWERS -> {
                cloud(c, cx, cy - size * 0.12f, size * 0.9f, CLOUD)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
                for (i in -1..1) c.drawCircle(cx + i * size * 0.24f, cy + size * 0.3f, size * 0.055f, p)
            }
            WeatherKind.THUNDER -> {
                cloud(c, cx, cy - size * 0.12f, size * 0.9f, CLOUD_DARK)
                val bolt = Path().apply {
                    moveTo(cx + size * 0.04f, cy + size * 0.08f); lineTo(cx - size * 0.12f, cy + size * 0.3f); lineTo(cx - size * 0.01f, cy + size * 0.3f)
                    lineTo(cx - size * 0.08f, cy + size * 0.5f); lineTo(cx + size * 0.14f, cy + size * 0.24f); lineTo(cx + size * 0.03f, cy + size * 0.24f); close()
                }
                c.drawPath(bolt, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SUN })
            }
        }
    }

    private fun cloud(c: Canvas, cx: Float, cy: Float, width: Float, color: Int) =
        SkyArt.drawCloud(c, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }, cx, cy + width * 0.08f, width)

    /** Sun (disc with rays) by day, a crescent moon at night. */
    private fun body(c: Canvas, day: Boolean, cx: Float, cy: Float, r: Float, behind: Int) {
        if (day) {
            c.drawCircle(cx, cy, r * 0.62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SUN })
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SUN; strokeWidth = r * 0.16f; strokeCap = Paint.Cap.ROUND }
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val s = kotlin.math.sin(a).toFloat(); val co = kotlin.math.cos(a).toFloat()
                c.drawLine(cx + s * r * 0.85f, cy - co * r * 0.85f, cx + s * r * 1.05f, cy - co * r * 1.05f, p)
            }
        } else {
            c.drawCircle(cx, cy, r * 0.75f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9D6CB.toInt() })
            c.drawCircle(cx + r * 0.38f, cy - r * 0.2f, r * 0.68f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = behind })
        }
    }
}
