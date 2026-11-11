package de.schumey.overhead

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The sky strip shared by the poster and the weather widget: time of day from the real sun position, the real moon
 * phase, stars, clouds, and rain, snow, fog or lightning when the weather says so.
 */
object SkyArt {
    /** How the sky looks right now. [cloud] is 0..1, [precipMm] the current precipitation. */
    class Conditions(
        val cloud: Float = 0.4f,
        val precipMm: Double = 0.0,
        val snow: Boolean = false,
        val fog: Boolean = false,
        val thunder: Boolean = false,
    )

    internal fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t)).roundToInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun stop(night: Int, dusk: Int, day: Int, f: Float) =
        if (f < 0.5f) lerp(night, dusk, f * 2) else lerp(dusk, day, (f - 0.5f) * 2)

    /** 0 = night, 1 = day, smooth through twilight; night if the position is unknown. */
    fun daylight(lat: Double?, lon: Double?, now: Long): Float =
        if (lat != null && lon != null) ((Sky.sunElevationDeg(lat, lon, now) + 6) / 12).coerceIn(0.0, 1.0).toFloat() else 0f

    /**
     * Draws the sky into [0, w] x [0, skyH]. [headerH] keeps the sun and moon below the header text.
     * [bodyScale] enlarges sun and moon (the weather widget makes them a main character).
     */
    fun draw(
        c: Canvas, lat: Double?, lon: Double?, now: Long, cond: Conditions, w: Float, skyH: Float, d: Float, headerH: Float,
        bodyScale: Float = 1f,
    ) {
        val f = daylight(lat, lon, now)
        val cloud = cond.cloud
        val haze = if (cond.fog) 0.55f else 1f // fog hides stars, sun and moon

        var top = stop(0xFF1C1B18.toInt(), 0xFF3B3F63.toInt(), 0xFF5B8DB8.toInt(), f)
        var bottom = stop(0xFF282723.toInt(), 0xFFD98A5B.toInt(), 0xFFB4D2E6.toInt(), f)
        if (f > 0.5f && (cloud > 0.7f || cond.fog || cond.precipMm >= 0.05)) { // overcast or wet day: grey it down
            val g = (max((cloud - 0.7f) / 0.3f * 0.5f, if (cond.fog || cond.precipMm >= 0.05) 0.55f else 0f)) * f
            top = lerp(top, 0xFF8794A0.toInt(), g); bottom = lerp(bottom, 0xFFA9B2BA.toInt(), g)
        }
        c.drawRect(0f, 0f, w, skyH, Paint().apply { shader = LinearGradient(0f, 0f, 0f, skyH, top, bottom, Shader.TileMode.CLAMP) })

        val rnd = Random(now / 600_000L * 31 + 7) // the scene changes every 10 minutes, not on every redraw
        c.save()
        c.clipRect(0f, 0f, w, skyH)

        // Stars
        if (f < 0.6f) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
            val n = max(10, (46 * (1 - cloud)).toInt())
            repeat(n) {
                sp.alpha = (((60 + rnd.nextInt(130)) * (1 - f / 0.6f)) * haze).toInt()
                c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * skyH * 0.7f, (0.5f + rnd.nextFloat() * 1.1f) * d, sp)
            }
        }
        // Moon (real phase) at night, sun by day
        val bodyX = if (f < 0.6f) w * 0.86f else w * 0.14f
        val bodyR = 15f * d * bodyScale
        val bodyY = max(headerH + bodyR + 3f * d, skyH * 0.30f)
        if (f < 0.6f) drawMoon(c, bodyX, bodyY, bodyR, Sky.moonPhase(now), (((1 - f / 0.6f) * 255) * haze).toInt())
        else if (f > 0.7f && cloud < 0.6f) {
            c.drawCircle(bodyX, bodyY, bodyR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE9A8.toInt(); alpha = (f * 255 * haze).toInt() })
        }

        // Clouds: they keep clear of the moon (or sun) so it stays visible even under an overcast sky
        val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lerp(0xFF3A3935.toInt(), lerp(0xFFFFFFFF.toInt(), 0xFFC4CBD2.toInt(), cloud), f)
            alpha = 235
        }
        repeat((cloud * 4).roundToInt().coerceAtLeast(if (cloud > 0.15f) 1 else 0)) {
            var cw: Float
            var cx: Float
            var cy: Float
            var tries = 0
            do {
                cw = w * (0.22f + rnd.nextFloat() * 0.24f)
                cx = rnd.nextFloat() * w
                cy = skyH * (0.14f + rnd.nextFloat() * 0.6f)
                tries++
            } while (tries < 10 && abs(cx - bodyX) < cw * 0.55f + bodyR * 1.8f && abs(cy - bodyY) < cw * 0.3f + bodyR * 1.8f)
            drawCloud(c, cp, cx, cy, cw)
        }

        // Fog: pale bands across the strip
        if (cond.fog) {
            val fp = Paint().apply { color = if (f > 0.5f) 0xFFFFFFFF.toInt() else 0xFFB4B1A6.toInt() }
            for (i in 0 until 4) {
                fp.alpha = 38
                val y = skyH * (0.25f + i * 0.19f)
                c.drawRect(0f, y, w, y + skyH * 0.13f, fp)
            }
        }

        val dayRain = f > 0.5f
        if (cond.snow) {
            // Snow: soft dots drifting down
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
            repeat(min(120, (30 + cond.precipMm * 25).toInt())) {
                sp.alpha = 110 + rnd.nextInt(120)
                c.drawCircle(rnd.nextFloat() * w, skyH * 0.12f + rnd.nextFloat() * skyH * 0.9f, (0.9f + rnd.nextFloat() * 1.6f) * d, sp)
            }
        } else if (cond.precipMm >= 0.05) {
            // Rain: slanted streaks
            c.drawRect(0f, skyH * 0.28f, w, skyH, Paint().apply { color = 0xFFFFFFFF.toInt(); alpha = 14 })
            val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
            repeat(min(150, (24 + cond.precipMm * 30).toInt())) {
                val len = (14 + rnd.nextFloat() * 34f) * d
                val x = rnd.nextFloat() * (w + len)
                val y = skyH * 0.12f + rnd.nextFloat() * skyH * 0.9f
                rp.strokeWidth = (0.7f + rnd.nextFloat() * 0.9f) * d
                rp.color = if (dayRain) 0xFF5E6B77.toInt() else 0xFFB4B1A6.toInt()
                rp.alpha = 40 + rnd.nextInt(80) // after the colour: setting a colour resets the alpha
                c.drawLine(x + len * 0.22f, y - len, x, y, rp)
            }
        }

        // Lightning: a faint flash and one bolt
        if (cond.thunder) {
            c.drawRect(0f, 0f, w, skyH, Paint().apply { color = 0xFFFFFFFF.toInt(); alpha = 16 })
            val bx = w * (0.35f + rnd.nextFloat() * 0.3f)
            // the bolt starts below the header text and ends short of the bottom edge
            val top = headerH + 4f * d
            val span = (skyH * 0.92f - top).coerceAtLeast(20f * d)
            fun y(frac: Float) = top + span * frac
            val bolt = Path().apply {
                moveTo(bx, y(0f)); lineTo(bx - 7f * d, y(0.42f)); lineTo(bx + 1f * d, y(0.42f))
                lineTo(bx - 9f * d, y(1f)); lineTo(bx + 11f * d, y(0.34f)); lineTo(bx + 3f * d, y(0.34f))
                lineTo(bx + 9f * d, y(0f)); close()
            }
            c.drawPath(bolt, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE9A8.toInt(); alpha = 235 })
        }
        c.restore()
    }

    fun drawCloud(c: Canvas, p: Paint, cx: Float, cy: Float, w: Float) {
        val h = w * 0.26f
        c.drawOval(RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f), p)
        c.drawOval(RectF(cx - w * 0.30f, cy - h * 1.15f, cx + w * 0.10f, cy + h * 0.2f), p)
        c.drawOval(RectF(cx - w * 0.04f, cy - h * 0.85f, cx + w * 0.34f, cy + h * 0.25f), p)
    }

    /** Moon with its real phase: [phase] 0 = new, 0.5 = full, waxing lit on the right (northern hemisphere). */
    fun drawMoon(c: Canvas, cx: Float, cy: Float, r: Float, phase: Double, alpha: Int) {
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3935.toInt(); this.alpha = alpha })
        val k = cos(2 * Math.PI * phase).toFloat() // 1 new .. -1 full
        val s = if (phase < 0.5) 1f else -1f
        val path = Path()
        path.arcTo(oval, -90f, 180f * s, true)
        val term = RectF(cx - abs(k) * r, cy - r, cx + abs(k) * r, cy + r)
        path.arcTo(term, 90f, -180f * s * sign(k), false)
        path.close()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9D6CB.toInt(); this.alpha = alpha })
    }
}
