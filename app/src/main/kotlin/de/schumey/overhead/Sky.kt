package de.schumey.overhead

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Sun and moon from the clock and the position alone (no network): day/night sky and the moon phase on the poster. */
object Sky {
    private const val MS_PER_DAY = 86_400_000.0
    private const val SYNODIC_DAYS = 29.530588853
    /** A known new moon, 2000-01-06 14:24 UTC, in days since the Unix epoch. */
    private const val NEW_MOON_EPOCH_DAYS = 10962.6

    private fun mod(x: Double, m: Double) = ((x % m) + m) % m

    /** Height of the sun above the horizon in degrees (negative = below), low-precision astronomical formula. */
    fun sunElevationDeg(lat: Double, lon: Double, millis: Long): Double {
        val n = millis / MS_PER_DAY + 2440587.5 - 2451545.0 // days since J2000.0
        val meanLon = mod(280.460 + 0.9856474 * n, 360.0)
        val g = Math.toRadians(mod(357.528 + 0.9856003 * n, 360.0))
        val lambda = Math.toRadians(meanLon + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val dec = asin(sin(eps) * sin(lambda))
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val gmstHours = mod(18.697374558 + 24.06570982441908 * n, 24.0)
        val ha = Math.toRadians(mod(gmstHours * 15 + lon, 360.0)) - ra
        val latR = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(latR) * sin(dec) + cos(latR) * cos(dec) * cos(ha)))
    }

    /** Moon phase 0..1: 0 = new moon, 0.5 = full moon, rising values = waxing. */
    fun moonPhase(millis: Long): Double = mod((millis / MS_PER_DAY - NEW_MOON_EPOCH_DAYS) / SYNODIC_DAYS, 1.0)
}
