package de.schumey.overhead

import org.json.JSONObject
import java.util.Locale

/** Current cloud cover and precipitation from Open-Meteo (free, no key). Only drives the sky on the poster. */
object Weather {
    private const val URL = "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current=cloud_cover,precipitation"
    private const val TTL_MS = 10 * 60_000L

    data class Now(val cloudCover: Int?, val precipMm: Double?)

    fun parse(json: String): Now {
        val cur = JSONObject(json).optJSONObject("current") ?: return Now(null, null)
        return Now(cur.int("cloud_cover"), cur.dbl("precipitation"))
    }

    /** Cached for 10 minutes per rounded position. Returns the last known value if the request fails. */
    fun current(prefs: Prefs, lat: Double, lon: Double, now: Long): Now {
        val key = key(lat, lon)
        val cached = try { prefs.weatherJson?.let { JSONObject(it) } } catch (e: Exception) { null }
        if (cached != null && cached.optString("k") == key && now - cached.optLong("t") in 0..TTL_MS) {
            return Now(cached.int("c"), cached.dbl("p"))
        }
        return try {
            val w = parse(FlightApi.get(URL.format(fmt(lat), fmt(lon))).second)
            prefs.weatherJson = JSONObject().put("t", now).put("k", key).putOpt("c", w.cloudCover).putOpt("p", w.precipMm).toString()
            w
        } catch (e: Exception) {
            cached?.let { Now(it.int("c"), it.dbl("p")) } ?: Now(null, null)
        }
    }

    private fun fmt(v: Double) = "%.2f".format(Locale.US, v)
    private fun key(lat: Double, lon: Double) = "${fmt(lat)},${fmt(lon)}"
}
