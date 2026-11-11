package de.schumey.overhead

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Aircraft positions: adsb.lol (free, no key). Routes (origin/destination): adsbdb.com (free, no key).
 * The observer's exact position never leaves the device: the query uses a point rounded to about 1 km
 * and a radius that is 2 km larger; distances are then computed here from the exact position.
 */
object FlightApi {
    private const val POINT_URL = "https://api.adsb.lol/v2/point/%s/%s/%d"
    private const val ROUTE_URL = "https://api.adsbdb.com/v0/callsign/%s"
    private const val KM_PER_NM = 1.852
    private const val MAX_RADIUS_NM = 250
    private const val FT_TO_M = 0.3048

    /** Airline-style callsigns (ABC123, ABC12D); general aviation registrations have no scheduled route. */
    private val AIRLINE_CALLSIGN = Regex("^[A-Z]{3}[0-9]{1,4}[A-Z]{0,2}$")

    fun looksLikeAirlineCallsign(cs: String) = AIRLINE_CALLSIGN.matches(cs)

    @Throws(IOException::class)
    fun fetchNearby(lat: Double, lon: Double, radiusKm: Int): List<Flight> {
        val nm = min(MAX_RADIUS_NM, ceil((radiusKm + 2) / KM_PER_NM).toInt())
        val url = POINT_URL.format(java.util.Locale.US, "%.2f".format(java.util.Locale.US, lat), "%.2f".format(java.util.Locale.US, lon), nm)
        val body = get(url).second
        return parseNearby(body, lat, lon).filter { it.distanceKm <= radiusKm }
    }

    /** Route for a callsign, or null if the database does not know it (HTTP 404). Other failures throw. */
    @Throws(IOException::class)
    fun fetchRoute(callsign: String): RouteInfo? {
        val (code, body) = get(ROUTE_URL.format(callsign), allow404 = true)
        if (code == 404) return null
        return parseRoute(body)
    }

    fun parseNearby(json: String, obsLat: Double, obsLon: Double): List<Flight> {
        val arr = JSONObject(json).optJSONArray("ac") ?: return emptyList()
        val out = ArrayList<Flight>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.dbl("lat") ?: continue
            val lon = o.dbl("lon") ?: continue
            val hex = o.optString("hex").uppercase()
            val reg = o.str("r")
            val altRaw = o.opt("alt_baro")
            val onGround = altRaw == "ground"
            val altM = (altRaw as? Number)?.let { (it.toDouble() * FT_TO_M).roundToInt() }
            val callsign = o.optString("flight").trim().ifEmpty { reg ?: hex }
            val distance = Geo.distanceKm(obsLat, obsLon, lat, lon)
            val altKm = (altM ?: 0) / 1000.0
            out += Flight(
                hex = hex,
                callsign = callsign,
                registration = reg,
                type = o.str("t"),
                altitudeM = altM,
                onGround = onGround,
                speedKmh = o.dbl("gs")?.let { (it * KM_PER_NM).roundToInt() },
                track = o.dbl("track"),
                vertRateFpm = o.int("baro_rate") ?: o.int("geom_rate"),
                lat = lat,
                lon = lon,
                distanceKm = distance,
                bearing = Geo.bearing(obsLat, obsLon, lat, lon),
                slantKm = hypot(distance, altKm),
            )
        }
        return out
    }

    fun parseRoute(json: String): RouteInfo? {
        val fr = JSONObject(json).optJSONObject("response")?.optJSONObject("flightroute") ?: return null
        fun airport(o: JSONObject?): Airport? {
            if (o == null) return null
            val lat = o.dbl("latitude") ?: return null
            val lon = o.dbl("longitude") ?: return null
            val code = o.str("iata_code") ?: o.str("icao_code") ?: return null
            return Airport(code, o.str("municipality") ?: o.str("name") ?: "", lat, lon)
        }
        val airline = fr.optJSONObject("airline")
        return RouteInfo(
            airline = airline?.str("name"),
            origin = airport(fr.optJSONObject("origin")),
            destination = airport(fr.optJSONObject("destination")),
            flightNo = fr.str("callsign_iata") ?: fr.str("callsign"),
            airlineIcao = airline?.str("icao"),
        )
    }

    internal fun get(url: String, allow404: Boolean = false, readTimeoutMs: Int = 7_000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", "Overhead-Android/0.1")
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code == 404 && allow404) return code to ""
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            return code to c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
