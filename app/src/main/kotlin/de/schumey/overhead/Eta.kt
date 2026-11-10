package de.schumey.overhead

/**
 * Arrival estimate. No free source publishes real schedules, so this is remaining great-circle distance
 * divided by current ground speed. It is a rough figure (ignores routing, wind changes and the slower approach).
 */
object Eta {
    private const val MIN_SPEED_KMH = 150

    fun estimate(f: Flight, now: Long): Long? {
        val dest = f.route?.destination ?: return null
        val speed = f.speedKmh ?: return null
        if (f.onGround || speed < MIN_SPEED_KMH) return null
        val remainingKm = Geo.distanceKm(f.lat, f.lon, dest.lat, dest.lon)
        // The callsign may belong to another leg (callsigns are reused) or the aircraft may be turning away:
        // if it clearly flies away from the destination, an estimate would be wrong, so give none.
        val track = f.track
        if (track != null && remainingKm > 30 &&
            Geo.angleDiff(track, Geo.bearing(f.lat, f.lon, dest.lat, dest.lon)) > 100
        ) return null
        val hours = remainingKm / speed
        return now + (hours * 3_600_000).toLong()
    }
}
