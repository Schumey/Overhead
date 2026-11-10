package de.schumey.overhead

/**
 * Arrival estimate. No free source publishes real schedules, so this is remaining great-circle distance
 * divided by current ground speed. It is a rough figure (ignores routing, wind changes and the slower approach).
 */
object Eta {
    private const val MIN_SPEED_KMH = 150

    /**
     * The route database stores one direction per callsign, but callsigns are flown both ways. If the aircraft clearly
     * heads away from the stored destination and toward the stored origin, the leg is the opposite one: swap them.
     */
    fun orient(f: Flight): Flight {
        val r = f.route ?: return f
        val origin = r.origin ?: return f
        val dest = r.destination ?: return f
        val track = f.track ?: return f
        if (f.onGround) return f
        if (Geo.distanceKm(f.lat, f.lon, dest.lat, dest.lon) < 30 || Geo.distanceKm(f.lat, f.lon, origin.lat, origin.lon) < 30) return f
        val awayFromDest = Geo.angleDiff(track, Geo.bearing(f.lat, f.lon, dest.lat, dest.lon)) > 100
        val towardOrigin = Geo.angleDiff(track, Geo.bearing(f.lat, f.lon, origin.lat, origin.lon)) < 60
        return if (awayFromDest && towardOrigin) f.copy(route = r.copy(origin = dest, destination = origin)) else f
    }

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
