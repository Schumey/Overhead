package de.schumey.overhead

import android.content.Context
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("overhead", Context.MODE_PRIVATE)

    /** true: current position; false: the saved fixed place. */
    var useGps: Boolean
        get() = sp.getBoolean("useGps", true)
        set(v) = sp.edit().putBoolean("useGps", v).apply()

    var fixedLat: Double?
        get() = sp.getString("fixedLat", null)?.toDoubleOrNull()
        private set(v) = sp.edit().putString("fixedLat", v?.toString()).apply()

    var fixedLon: Double?
        get() = sp.getString("fixedLon", null)?.toDoubleOrNull()
        private set(v) = sp.edit().putString("fixedLon", v?.toString()).apply()

    fun saveFixed(lat: Double, lon: Double) {
        fixedLat = lat
        fixedLon = lon
    }

    var radiusKm: Int
        get() = sp.getInt("radiusKm", 25)
        set(v) = sp.edit().putInt("radiusKm", v).apply()

    var intervalMin: Int
        get() = sp.getInt("intervalMin", 2)
        set(v) = sp.edit().putInt("intervalMin", v).apply()

    var snapshotJson: String?
        get() = sp.getString("snapshot", null)
        set(v) = sp.edit().putString("snapshot", v).apply()

    // "routes2": the route format gained flightNo/airlineIcao, so older cached entries are ignored.
    var routesJson: String?
        get() = sp.getString("routes2", null)
        set(v) = sp.edit().putString("routes2", v).apply()

    /** The weather widget's data: {"t":fetched,"lat","lon","place","raw":Open-Meteo response,"err":optional}. */
    var forecastJson: String?
        get() = sp.getString("forecast", null)
        set(v) = sp.edit().putString("forecast", v).apply()

    /** Last airport lookup: {"t":time,"k":"lat,lon" (0.1 degree grid),"a":airport or absent if none was found}. */
    var airportJson: String?
        get() = sp.getString("airport", null)
        set(v) = sp.edit().putString("airport", v).apply()

    /** The look shown on the app's start screen. */
    var appStyle: Style
        get() = Style.of(sp.getString("appStyle", null))
        set(v) = sp.edit().putString("appStyle", v.key).apply()

    /** The dashboard's flight look; POSTER there means the sky shared with the weather. */
    var dashStyle: Style
        get() = Style.of(sp.getString("dashStyle", null))
        set(v) = sp.edit().putString("dashStyle", v.key).apply()

    /** The look chosen for one widget instance. */
    fun styleOf(widgetId: Int): Style = Style.of(sp.getString("style_$widgetId", null))

    fun setStyle(widgetId: Int, style: Style) = sp.edit().putString("style_$widgetId", style.key).apply()

    fun forgetWidget(widgetId: Int) = sp.edit().remove("style_$widgetId").apply()

    /** Last weather lookup: {"t":time,"k":"lat,lon","c":cloud,"p":precip}. */
    var weatherJson: String?
        get() = sp.getString("weather", null)
        set(v) = sp.edit().putString("weather", v).apply()

    /** Phone calendars shown in the calendar widget; null = every calendar the phone shows. */
    var calendarIds: Set<Long>?
        get() = sp.getString("calIds", null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet()
        set(v) = sp.edit().putString("calIds", v?.joinToString(",")).apply()

    /** ICS/webcal links added by the user (normalized to https). */
    var calendarUrls: List<String>
        get() = sp.getString("calUrls", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = sp.edit().putString("calUrls", v.joinToString("\n")).apply()

    /** Last place-name lookup: {"t":time,"k":"lat,lon","n":name} (name empty if the lookup failed). */
    var placeJson: String?
        get() = sp.getString("place", null)
        set(v) = sp.edit().putString("place", v).apply()
}

/**
 * Persistent route cache keyed by callsign. A callsign keeps its route for a long time, so a hit saves a request;
 * "unknown" results are remembered for a shorter time so a database update can still be picked up.
 */
object RouteCache {
    private const val HIT_TTL_MS = 12 * 3_600_000L
    private const val MISS_TTL_MS = 30 * 60_000L
    private const val MAX_ENTRIES = 300

    /** Returns null on a cache miss, otherwise the cached value (which can itself be "no route known"). */
    fun lookup(store: JSONObject, callsign: String, now: Long): Result? {
        val e = store.optJSONObject(callsign) ?: return null
        val age = now - e.optLong("t")
        val route = e.optJSONObject("r")
        val ttl = if (route != null) HIT_TTL_MS else MISS_TTL_MS
        if (age !in 0..ttl) return null
        return Result(route?.let { routeFromJson(it) })
    }

    fun put(store: JSONObject, callsign: String, route: RouteInfo?, now: Long) {
        store.put(callsign, JSONObject().put("t", now).apply { route?.let { put("r", it.toJson()) } })
        if (store.length() > MAX_ENTRIES) {
            // Drop the oldest entries.
            val keys = store.keys().asSequence().toList().sortedBy { store.getJSONObject(it).optLong("t") }
            keys.take(store.length() - MAX_ENTRIES).forEach { store.remove(it) }
        }
    }

    fun load(prefs: Prefs): JSONObject = try {
        JSONObject(prefs.routesJson ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    data class Result(val route: RouteInfo?)
}
