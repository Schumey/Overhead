package de.schumey.overhead

import org.json.JSONArray
import org.json.JSONObject

data class Airport(val iata: String, val city: String, val lat: Double, val lon: Double)

data class RouteInfo(
    val airline: String?,
    val origin: Airport?,
    val destination: Airport?,
    /** Passenger-style flight number (AF274) when the database has one; the callsign (AFR274) otherwise. */
    val flightNo: String? = null,
    /** ICAO code of the airline (AFR), used to pick livery colours. */
    val airlineIcao: String? = null,
)

data class Flight(
    val hex: String,
    val callsign: String,
    val registration: String?,
    val type: String?,
    /** Barometric altitude in metres, null if unknown. */
    val altitudeM: Int?,
    val onGround: Boolean,
    val speedKmh: Int?,
    val track: Double?,
    val vertRateFpm: Int?,
    val lat: Double,
    val lon: Double,
    /** Ground distance from the observer, km. */
    val distanceKm: Double,
    /** Bearing from the observer to the aircraft, degrees. */
    val bearing: Double,
    /** Straight-line distance including altitude, km. Used for sorting ("what is closest to me"). */
    val slantKm: Double,
    val route: RouteInfo? = null,
    val etaMillis: Long? = null,
)

data class Runway(val ref: String, val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double)

/** The airport nearest to the observer (from OpenStreetMap), with its runways, for the runway view. */
data class AirportInfo(
    val icao: String,
    val iata: String?,
    val name: String,
    val lat: Double,
    val lon: Double,
    val runways: List<Runway> = emptyList(),
)

/** The last result of a refresh. Stored as JSON so the widget, the app and a later process all see the same data. */
data class Snapshot(
    val time: Long,
    val lat: Double?,
    val lon: Double?,
    val radiusKm: Int,
    val flights: List<Flight>,
    val error: String?,
    /** Name of the place under the observer (city), for the poster headline. */
    val place: String? = null,
    /** Cloud cover in percent and precipitation in mm, for the sky on the poster. */
    val cloudCover: Int? = null,
    val precipMm: Double? = null,
    /** Only filled while a runway view is in use. [airportTraffic] is measured from the airport, not from the observer. */
    val airport: AirportInfo? = null,
    val airportTraffic: List<Flight> = emptyList(),
) {
    fun toJson(): String = JSONObject().apply {
        put("time", time)
        putOpt("lat", lat)
        putOpt("lon", lon)
        put("radiusKm", radiusKm)
        putOpt("error", error)
        putOpt("place", place)
        putOpt("cloud", cloudCover)
        putOpt("precip", precipMm)
        airport?.let { put("airport", it.toJson()) }
        if (airportTraffic.isNotEmpty()) put("traffic", JSONArray().also { arr -> airportTraffic.forEach { arr.put(it.toJson()) } })
        put("flights", JSONArray().also { arr -> flights.forEach { arr.put(it.toJson()) } })
    }.toString()

    companion object {
        const val ERR_LOCATION = "location"
        const val ERR_NETWORK = "network"

        fun fromJson(s: String?): Snapshot? {
            if (s.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(s)
                val arr = o.optJSONArray("flights") ?: JSONArray()
                Snapshot(
                    time = o.optLong("time"),
                    lat = o.dbl("lat"),
                    lon = o.dbl("lon"),
                    radiusKm = o.optInt("radiusKm", 25),
                    flights = (0 until arr.length()).map { flightFromJson(arr.getJSONObject(it)) },
                    error = o.str("error"),
                    place = o.str("place"),
                    cloudCover = o.int("cloud"),
                    precipMm = o.dbl("precip"),
                    airport = o.optJSONObject("airport")?.let { airportInfoFromJson(it) },
                    airportTraffic = o.optJSONArray("traffic")?.let { t -> (0 until t.length()).map { flightFromJson(t.getJSONObject(it)) } }
                        ?: emptyList(),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

// --- JSON helpers (org.json returns 0/""/NaN for missing keys, which we do not want to mistake for data) ---

internal fun JSONObject.dbl(k: String): Double? =
    if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

internal fun JSONObject.int(k: String): Int? = dbl(k)?.toInt()

internal fun JSONObject.str(k: String): String? =
    if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

fun AirportInfo.toJson(): JSONObject = JSONObject().apply {
    put("icao", icao); putOpt("iata", iata); put("name", name); put("lat", lat); put("lon", lon)
    put("runways", JSONArray().also { arr ->
        runways.forEach { arr.put(JSONObject().put("ref", it.ref).put("lat1", it.lat1).put("lon1", it.lon1).put("lat2", it.lat2).put("lon2", it.lon2)) }
    })
}

fun airportInfoFromJson(o: JSONObject): AirportInfo {
    val rw = o.optJSONArray("runways")
    return AirportInfo(
        o.optString("icao"), o.str("iata"), o.optString("name"), o.optDouble("lat"), o.optDouble("lon"),
        if (rw == null) emptyList() else (0 until rw.length()).map {
            val r = rw.getJSONObject(it)
            Runway(r.optString("ref"), r.optDouble("lat1"), r.optDouble("lon1"), r.optDouble("lat2"), r.optDouble("lon2"))
        },
    )
}

private fun Airport.toJson() = JSONObject().put("iata", iata).put("city", city).put("lat", lat).put("lon", lon)

private fun airportFromJson(o: JSONObject?): Airport? =
    o?.let { Airport(it.optString("iata"), it.optString("city"), it.optDouble("lat"), it.optDouble("lon")) }

fun RouteInfo.toJson(): JSONObject = JSONObject().apply {
    putOpt("airline", airline)
    putOpt("flightNo", flightNo)
    putOpt("airlineIcao", airlineIcao)
    origin?.let { put("origin", it.toJson()) }
    destination?.let { put("destination", it.toJson()) }
}

fun routeFromJson(o: JSONObject): RouteInfo =
    RouteInfo(
        o.str("airline"), airportFromJson(o.optJSONObject("origin")), airportFromJson(o.optJSONObject("destination")),
        o.str("flightNo"), o.str("airlineIcao"),
    )

private fun Flight.toJson(): JSONObject = JSONObject().apply {
    put("hex", hex); put("callsign", callsign); putOpt("registration", registration); putOpt("type", type)
    putOpt("altitudeM", altitudeM); put("onGround", onGround); putOpt("speedKmh", speedKmh)
    putOpt("track", track); putOpt("vertRateFpm", vertRateFpm)
    put("lat", lat); put("lon", lon); put("distanceKm", distanceKm); put("bearing", bearing); put("slantKm", slantKm)
    route?.let { put("route", it.toJson()) }
    putOpt("etaMillis", etaMillis)
}

private fun flightFromJson(o: JSONObject) = Flight(
    hex = o.optString("hex"),
    callsign = o.optString("callsign"),
    registration = o.str("registration"),
    type = o.str("type"),
    altitudeM = o.int("altitudeM"),
    onGround = o.optBoolean("onGround"),
    speedKmh = o.int("speedKmh"),
    track = o.dbl("track"),
    vertRateFpm = o.int("vertRateFpm"),
    lat = o.optDouble("lat"),
    lon = o.optDouble("lon"),
    distanceKm = o.optDouble("distanceKm"),
    bearing = o.optDouble("bearing"),
    slantKm = o.optDouble("slantKm"),
    route = o.optJSONObject("route")?.let { routeFromJson(it) },
    etaMillis = if (o.has("etaMillis")) o.optLong("etaMillis") else null,
)
