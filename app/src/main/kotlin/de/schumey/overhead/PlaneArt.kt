package de.schumey.overhead

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * Flat side-view aircraft illustrations. Everything is drawn in a unit system where the fuselage is
 * 1.0 long (nose at +x, y down) and scaled to the requested length; the canvas is mirrored for west-bound traffic.
 */
object PlaneArt {
    private const val HULL = 0xFFF2F0E8.toInt()
    private const val OUTLINE = 0xFF8C8A84.toInt()
    private const val DARK = 0xFF1B1A17.toInt()

    private val helicopters = setOf(
        "EC35", "EC45", "EC30", "H160", "A109", "B06", "B407", "R44", "R22", "AS50", "AS65", "H47", "NH90", "EC55", "S76", "A139",
        "H135", "H145", "EC20", "AS32", "B412", "B429", "MI8", "H500", "S92",
    )
    private val wide = listOf("B74", "B76", "B77", "B78", "A30", "A31", "A33", "A34", "A35", "A38", "MD11", "IL96", "A124", "A225")
    private val regional = listOf("E1", "E2", "E7", "CRJ", "AT4", "AT7", "DH8", "SF3", "SU9", "BCS", "RJ", "F70", "F100", "B462")
    private val small = listOf(
        "C1", "C2", "C5", "C6", "C7", "PA", "SR2", "DA4", "PC1", "PC2", "TBM", "E55", "E50", "F2T", "F90", "FA", "GLF", "GL5", "GL7",
        "GLE", "CL3", "CL6", "LJ", "H25", "BE", "M20", "P28",
    )

    fun isHelicopter(type: String?) = type != null && type.uppercase() in helicopters

    /** Relative drawn length by aircraft class, so a 777 looks bigger than an Embraer or a Cessna. */
    fun lengthFactor(type: String?): Float {
        val t = type?.uppercase() ?: return 1f
        return when {
            isHelicopter(t) -> 0.62f
            small.any { t.startsWith(it) } -> 0.6f
            wide.any { t.startsWith(it) } -> 1.18f
            regional.any { t.startsWith(it) } -> 0.84f
            else -> 1f
        }
    }

    fun draw(c: Canvas, cx: Float, cy: Float, length: Float, facingRight: Boolean, livery: Livery, type: String?) {
        c.save()
        c.translate(cx, cy)
        c.scale(if (facingRight) length else -length, length)
        if (isHelicopter(type)) heli(c, livery, length) else jet(c, livery, length)
        c.restore()
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    private fun stroke(lengthPx: Float, width: Float = 2.2f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = OUTLINE
        strokeWidth = width / lengthPx // keep a constant on-screen line width under the unit scale
        strokeJoin = Paint.Join.ROUND
    }

    private fun jet(c: Canvas, lv: Livery, lengthPx: Float) {
        val line = stroke(lengthPx)

        // Tail fin
        val fin = Path().apply {
            moveTo(-0.17f, -0.055f); lineTo(-0.40f, -0.265f); lineTo(-0.485f, -0.265f); lineTo(-0.50f, -0.03f); close()
        }
        c.drawPath(fin, fill(lv.primary)); c.drawPath(fin, line)
        emblem(c, lv, -0.44f, -0.175f, 0.05f, lengthPx)

        // Horizontal stabiliser (near side)
        val stab = Path().apply {
            moveTo(-0.37f, 0.00f); lineTo(-0.50f, 0.025f); lineTo(-0.505f, 0.05f); lineTo(-0.41f, 0.035f); close()
        }
        c.drawPath(stab, fill(HULL)); c.drawPath(stab, line)

        // Fuselage
        val body = Path().apply {
            moveTo(-0.50f, -0.045f)
            quadTo(-0.40f, -0.068f, -0.28f, -0.07f)
            lineTo(0.28f, -0.07f)
            cubicTo(0.40f, -0.07f, 0.50f, -0.035f, 0.50f, 0.01f)
            cubicTo(0.50f, 0.05f, 0.42f, 0.07f, 0.30f, 0.07f)
            lineTo(-0.20f, 0.07f)
            quadTo(-0.40f, 0.06f, -0.50f, 0.0f)
            close()
        }
        c.drawPath(body, fill(HULL))
        c.save()
        c.clipPath(body)
        val band = Path().apply { // belly band in the airline colour, with a slightly rising edge toward the tail
            moveTo(-0.6f, 0.0f); lineTo(-0.2f, 0.028f); lineTo(0.6f, 0.028f); lineTo(0.6f, 0.2f); lineTo(-0.6f, 0.2f); close()
        }
        c.drawPath(band, fill(lv.primary))
        c.restore()
        c.drawPath(body, line)

        // Cabin line, windows, cockpit
        c.drawLine(-0.30f, -0.046f, 0.30f, -0.046f, stroke(lengthPx, 1.4f))
        c.drawRoundRect(RectF(-0.08f, -0.022f, 0.30f, 0.016f), 0.02f, 0.02f, fill(DARK))
        val cockpit = Path().apply {
            moveTo(0.385f, -0.052f); lineTo(0.425f, -0.052f); lineTo(0.445f, -0.026f); lineTo(0.385f, -0.026f); close()
        }
        c.drawPath(cockpit, fill(DARK))

        // Wing (near side) and engine
        val wing = Path().apply {
            moveTo(0.02f, 0.06f); lineTo(-0.10f, 0.06f); lineTo(-0.27f, 0.165f); lineTo(-0.20f, 0.17f); close()
        }
        c.drawPath(wing, fill(HULL)); c.drawPath(wing, line)
        val engine = RectF(0.0f, 0.062f, 0.19f, 0.15f)
        c.drawRoundRect(engine, 0.04f, 0.04f, fill(lv.primary)); c.drawRoundRect(engine, 0.04f, 0.04f, line)
        val inlet = Path().apply { moveTo(0.19f, 0.09f); lineTo(0.19f, 0.125f); lineTo(0.168f, 0.107f); close() }
        c.drawPath(inlet, fill(HULL))
    }

    private fun emblem(c: Canvas, lv: Livery, x: Float, y: Float, r: Float, lengthPx: Float) {
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lv.accent; style = Paint.Style.STROKE; strokeWidth = 1.6f / lengthPx
        }
        when (lv.emblem) {
            Liveries.EMBLEM_GLOBE -> {
                c.drawCircle(x, y, r, white)
                c.drawLine(x - r, y, x + r, y, white)
                c.drawLine(x, y - r, x, y + r, white)
                c.drawOval(RectF(x - r * 0.45f, y - r, x + r * 0.45f, y + r), white)
            }
            Liveries.EMBLEM_STAR -> {
                val p = Path()
                for (i in 0 until 8) { // four-pointed star: long and short radii alternate
                    val a = Math.toRadians(i * 45.0 - 90)
                    val rr = if (i % 2 == 0) r * 1.15f else r * 0.35f
                    val px = x + (rr * cos(a)).toFloat()
                    val py = y + (rr * sin(a)).toFloat()
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                p.close()
                c.drawPath(p, fill(lv.accent))
            }
            Liveries.EMBLEM_STRIPES -> for (i in -1..1) {
                val o = i * r * 0.7f
                c.drawLine(x - r * 0.9f + o * 0.4f, y + o + r * 0.5f, x + r * 0.9f + o * 0.4f, y + o - r * 0.5f, white.apply { strokeWidth = 2.4f / lengthPx })
            }
            else -> c.drawCircle(x, y, r * 0.8f, fill(lv.accent))
        }
    }

    private fun heli(c: Canvas, lv: Livery, lengthPx: Float) {
        val line = stroke(lengthPx)
        // Tail boom and fin
        val boom = Path().apply {
            moveTo(-0.10f, -0.05f); lineTo(-0.48f, -0.035f); lineTo(-0.48f, -0.005f); lineTo(-0.10f, 0.03f); close()
        }
        c.drawPath(boom, fill(HULL)); c.drawPath(boom, line)
        val fin = Path().apply { moveTo(-0.40f, -0.04f); lineTo(-0.50f, -0.17f); lineTo(-0.44f, -0.17f); lineTo(-0.34f, -0.035f); close() }
        c.drawPath(fin, fill(lv.primary)); c.drawPath(fin, line)
        // Cabin
        val cabin = RectF(-0.14f, -0.12f, 0.30f, 0.09f)
        c.drawOval(cabin, fill(lv.primary)); c.drawOval(cabin, line)
        val glass = Path().apply { moveTo(0.10f, -0.095f); lineTo(0.22f, -0.07f); lineTo(0.26f, 0.0f); lineTo(0.10f, 0.0f); close() }
        c.drawPath(glass, fill(DARK))
        // Rotor and skids
        c.drawLine(0.08f, -0.12f, 0.08f, -0.155f, line)
        c.drawLine(-0.30f, -0.16f, 0.46f, -0.16f, stroke(lengthPx, 3.2f))
        c.drawLine(-0.10f, 0.13f, 0.28f, 0.13f, line)
        c.drawLine(-0.02f, 0.085f, -0.04f, 0.13f, line)
        c.drawLine(0.18f, 0.085f, 0.20f, 0.13f, line)
    }
}
