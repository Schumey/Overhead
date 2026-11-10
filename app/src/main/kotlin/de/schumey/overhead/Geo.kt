package de.schumey.overhead

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_KM = 6371.0088

    /** Great-circle distance in km (haversine). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_KM * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, 0..360 degrees (0 = north). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Smallest angle between two bearings, 0..180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return min(d, 360 - d)
    }

    /** Index 0..7 into N, NE, E, SE, S, SW, W, NW. */
    fun cardinalIndex(deg: Double): Int = (((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8
}
