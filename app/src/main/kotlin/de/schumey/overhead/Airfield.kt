package de.schumey.overhead

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The airport nearest to the observer and its runways, from OpenStreetMap (Overpass API, free, no key).
 * Cached per 0.1-degree grid cell, so it is looked up once per area, not on every refresh.
 */
object Airports {
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter?data="
    private const val HIT_TTL_MS = 30L * 24 * 3_600_000
    private const val MISS_TTL_MS = 6L * 3_600_000
    private const val FAILURE_BACKOFF_MS = 10L * 60_000
    private const val SEARCH_KM = 40.0
    private const val NEAR_NO_SERVICE_KM = 15.0
    private const val RUNWAY_RADIUS_KM = 6.0

    /** The answer for an area that is already known: [airport] is null if the area has none. */
    class Known(val airport: AirportInfo?)

    private fun key(lat: Double, lon: Double) = "%.1f,%.1f".format(Locale.US, lat, lon)

    /** What is stored for this area, without any network access; null if a lookup is due. */
    fun cached(prefs: Prefs, lat: Double, lon: Double, now: Long): Known? {
        val c = try { prefs.airportJson?.let { JSONObject(it) } } catch (e: Exception) { null } ?: return null
        if (c.optString("k") != key(lat, lon)) return null
        val age = now - c.optLong("t")
        val a = c.optJSONObject("a")
        return when {
            c.optBoolean("fail") -> if (age in 0..FAILURE_BACKOFF_MS) Known(null) else null
            a != null -> if (age in 0..HIT_TTL_MS) Known(airportInfoFromJson(a)) else null
            else -> if (age in 0..MISS_TTL_MS) Known(null) else null
        }
    }

    /**
     * Asks OpenStreetMap for the nearest airport and its runways in one request and stores the answer. The public
     * server can take 30 seconds or more, so this runs once per area (cached for 30 days) and a failure is remembered
     * for 10 minutes instead of being retried on every refresh.
     */
    fun fetch(prefs: Prefs, lat: Double, lon: Double, now: Long): AirportInfo? {
        val dLat = SEARCH_KM / 110.574
        val dLon = SEARCH_KM / (111.32 * cos(Math.toRadians(lat)))
        val box = "%.4f,%.4f,%.4f,%.4f".format(Locale.US, lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        // Aerodromes as centre points, runways with their full geometry (their two ends give the direction).
        val q = "[out:json][timeout:45];" +
            // Big airports (Frankfurt, Munich, ...) are mapped as relations, small airfields as nodes or ways.
            """(node["aeroway"="aerodrome"]["icao"]($box);way["aeroway"="aerodrome"]["icao"]($box);relation["aeroway"="aerodrome"]["icao"]($box););out center tags;""" +
            """way["aeroway"="runway"]($box);out geom tags;"""
        val state = JSONObject().put("t", now).put("k", key(lat, lon))
        try {
            val body = FlightApi.get(ENDPOINT + URLEncoder.encode(q, "UTF-8"), readTimeoutMs = 50_000).second
            val found = parseAerodromes(body, lat, lon)
            val full = found?.copy(runways = parseRunways(body, found))
            full?.let { state.put("a", it.toJson()) }
            prefs.airportJson = state.toString()
            return full
        } catch (e: Exception) {
            prefs.airportJson = state.put("fail", true).toString()
            throw e
        }
    }

    /** Picks the nearest airport with scheduled service (IATA code); failing that, a nearby one with an ICAO code. */
    fun parseAerodromes(json: String, lat: Double, lon: Double): AirportInfo? {
        val els = JSONObject(json).optJSONArray("elements") ?: return null
        val found = ArrayList<Pair<Double, AirportInfo>>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val icao = tags.str("icao") ?: continue
            val c = e.optJSONObject("center")
            val aLat = (c ?: e).dbl("lat") ?: continue
            val aLon = (c ?: e).dbl("lon") ?: continue
            val name = tags.str("name:de") ?: tags.str("name") ?: icao
            found += Geo.distanceKm(lat, lon, aLat, aLon) to AirportInfo(icao, tags.str("iata"), name, aLat, aLon)
        }
        found.sortBy { it.first }
        return (found.firstOrNull { it.second.iata != null } ?: found.firstOrNull { it.first <= NEAR_NO_SERVICE_KM })?.second
    }

    /** Runways from the response; with [near], only those within a few km of that airport's reference point. */
    fun parseRunways(json: String, near: AirportInfo? = null): List<Runway> {
        val els = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Runway>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val geom = e.optJSONArray("geometry") ?: continue
            if (geom.length() < 2) continue
            val tags = e.optJSONObject("tags")
            if (tags != null && tags.has("aeroway") && tags.optString("aeroway") != "runway") continue
            val a = geom.getJSONObject(0)
            val b = geom.getJSONObject(geom.length() - 1)
            val r = Runway(tags?.str("ref") ?: "", a.optDouble("lat"), a.optDouble("lon"), b.optDouble("lat"), b.optDouble("lon"))
            if (near != null && Geo.distanceKm(near.lat, near.lon, (r.lat1 + r.lat2) / 2, (r.lon1 + r.lon2) / 2) > RUNWAY_RADIUS_KM) continue
            out += r
        }
        return out
    }
}

/** What an aircraft at the airport is doing, for the runway view. Order = display priority. */
enum class FieldStatus { TAKEOFF, LANDED, DEPARTED, TAXI, STAND }

class FieldFlight(
    val flight: Flight,
    val status: FieldStatus,
    /** Distance from the airport reference point, km. */
    val km: Double,
    /** Index into the airport's runways if the aircraft is on one, else -1. */
    val runway: Int,
    /** Position along that runway, 0 at its first end to 1 at the other. */
    val along: Double,
)

object Airfield {
    private const val ON_RUNWAY_KM = 0.1
    private const val FAST_KMH = 60
    private const val STANDING_KMH = 9
    private const val DEPARTED_MAX_KM = 12.0
    private const val DEPARTED_MAX_ALT_M = 1800
    private const val CLIMB_FPM = 300

    fun classify(airport: AirportInfo, traffic: List<Flight>): List<FieldFlight> {
        val kx = 111.32 * cos(Math.toRadians(airport.lat))
        val ky = 110.574
        fun x(lon: Double) = (lon - airport.lon) * kx
        fun y(lat: Double) = (lat - airport.lat) * ky

        val out = ArrayList<FieldFlight>()
        for (f in traffic) {
            val km = Geo.distanceKm(f.lat, f.lon, airport.lat, airport.lon)
            val origin = f.route?.origin?.iata
            val dest = f.route?.destination?.iata
            val here = airport.iata
            val speed = f.speedKmh ?: 0

            var rwy = -1
            var along = 0.0
            if (f.onGround) {
                var best = Double.MAX_VALUE
                airport.runways.forEachIndexed { i, r ->
                    val ax = x(r.lon1); val ay = y(r.lat1)
                    val dx = x(r.lon2) - ax; val dy = y(r.lat2) - ay
                    val len2 = dx * dx + dy * dy
                    if (len2 == 0.0) return@forEachIndexed
                    val px = x(f.lon) - ax; val py = y(f.lat) - ay
                    val t = max(0.0, min(1.0, (px * dx + py * dy) / len2))
                    val dist = hypot(px - t * dx, py - t * dy)
                    if (dist < best) { best = dist; if (dist <= ON_RUNWAY_KM) { rwy = i; along = t } }
                }
            }

            val status = when {
                f.onGround && rwy >= 0 && speed >= FAST_KMH -> when {
                    here != null && dest == here -> FieldStatus.LANDED
                    else -> FieldStatus.TAKEOFF
                }
                f.onGround && speed < STANDING_KMH -> FieldStatus.STAND
                f.onGround -> FieldStatus.TAXI
                km <= DEPARTED_MAX_KM && (f.altitudeM ?: Int.MAX_VALUE) <= DEPARTED_MAX_ALT_M &&
                    ((here != null && origin == here) || (f.vertRateFpm ?: 0) >= CLIMB_FPM) -> FieldStatus.DEPARTED
                else -> continue // approaching or overflying: not part of the picture on the field
            }
            out += FieldFlight(f, status, km, if (status == FieldStatus.TAKEOFF || status == FieldStatus.LANDED || (f.onGround && rwy >= 0)) rwy else -1, along)
        }
        return out.sortedWith(compareBy({ it.status.ordinal }, { it.km }))
    }

    /** Aircraft that are about to depart: taxiing out or rolling for takeoff, with this airport as the origin. */
    fun beforeTakeoff(airport: AirportInfo, list: List<FieldFlight>): Int =
        list.count { (it.status == FieldStatus.TAKEOFF || it.status == FieldStatus.TAXI) && airport.iata != null && it.flight.route?.origin?.iata == airport.iata }

    fun justDeparted(list: List<FieldFlight>): Int = list.count { it.status == FieldStatus.DEPARTED }
}
