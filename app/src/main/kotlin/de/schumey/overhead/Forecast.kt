package de.schumey.overhead

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

data class HourPoint(val time: Long, val tempC: Double, val code: Int, val precipProb: Int?, val isDay: Boolean)

data class DayPoint(
    /** Start of the day (local time at the forecast location) as epoch millis. */
    val day: Long,
    val code: Int,
    val maxC: Double,
    val minC: Double,
    val precipMm: Double,
    val precipProb: Int?,
    val sunrise: Long?,
    val sunset: Long?,
)

data class Forecast(
    /** The moment the current observation is valid for, epoch millis. */
    val time: Long,
    val tempC: Double,
    val feelsC: Double?,
    val humidity: Int?,
    val precipMm: Double,
    val code: Int,
    val cloud: Int,
    val windKmh: Double?,
    val windDir: Int?,
    val isDay: Boolean,
    val hours: List<HourPoint>,
    val days: List<DayPoint>,
)

/** What the weather widget shows: the forecast (if any), where it is for, and what went wrong if anything. */
data class WeatherState(
    val forecast: Forecast?,
    /** When it was fetched. */
    val fetchedAt: Long,
    val lat: Double?,
    val lon: Double?,
    val place: String?,
    /** [Snapshot.ERR_LOCATION] or [Snapshot.ERR_NETWORK]; the forecast may still be an older one. */
    val error: String?,
)

/** WMO weather codes as used by Open-Meteo, grouped into the kinds the widget distinguishes. */
enum class WeatherKind(val labelRes: Int) {
    CLEAR(R.string.wx_clear), MOSTLY_CLEAR(R.string.wx_mostly_clear), PARTLY(R.string.wx_partly), OVERCAST(R.string.wx_overcast),
    FOG(R.string.wx_fog), DRIZZLE(R.string.wx_drizzle), RAIN(R.string.wx_rain), SHOWERS(R.string.wx_showers),
    SNOW(R.string.wx_snow), SNOW_SHOWERS(R.string.wx_snow_showers), THUNDER(R.string.wx_thunder);

    val isSnowy get() = this == SNOW || this == SNOW_SHOWERS
    val isWet get() = this == DRIZZLE || this == RAIN || this == SHOWERS || this == THUNDER || isSnowy

    companion object {
        fun of(code: Int): WeatherKind = when (code) {
            0 -> CLEAR
            1 -> MOSTLY_CLEAR
            2 -> PARTLY
            3 -> OVERCAST
            45, 48 -> FOG
            51, 53, 55, 56, 57 -> DRIZZLE
            61, 63, 65, 66, 67 -> RAIN
            80, 81, 82 -> SHOWERS
            71, 73, 75, 77 -> SNOW
            85, 86 -> SNOW_SHOWERS
            95, 96, 99 -> THUNDER
            else -> OVERCAST
        }
    }
}

object Forecasts {
    private const val URL = "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s" +
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m,is_day" +
        "&hourly=temperature_2m,precipitation_probability,weather_code,is_day" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,sunrise,sunset" +
        "&timezone=auto&forecast_days=7&wind_speed_unit=kmh"
    private const val TTL_MS = 15 * 60_000L

    fun parse(json: String): Forecast? {
        val o = JSONObject(json)
        val offset = ZoneOffset.ofTotalSeconds(o.optInt("utc_offset_seconds", 0))
        fun at(s: String) = LocalDateTime.parse(s).toEpochSecond(offset) * 1000
        fun day(s: String) = LocalDate.parse(s).atStartOfDay().toEpochSecond(offset) * 1000

        val cur = o.optJSONObject("current") ?: return null
        val hourly = o.optJSONObject("hourly")
        val daily = o.optJSONObject("daily")

        val hours = ArrayList<HourPoint>()
        if (hourly != null) {
            val t = hourly.getJSONArray("time")
            val temp = hourly.getJSONArray("temperature_2m")
            val code = hourly.optJSONArray("weather_code")
            val prob = hourly.optJSONArray("precipitation_probability")
            val isDay = hourly.optJSONArray("is_day")
            for (i in 0 until t.length()) {
                if (temp.isNull(i)) continue
                hours += HourPoint(
                    at(t.getString(i)), temp.getDouble(i), code?.optInt(i) ?: 0,
                    prob?.takeIf { !it.isNull(i) }?.getInt(i), isDay?.optInt(i, 1) != 0,
                )
            }
        }
        val days = ArrayList<DayPoint>()
        if (daily != null) {
            val t = daily.getJSONArray("time")
            val code = daily.optJSONArray("weather_code")
            val max = daily.getJSONArray("temperature_2m_max")
            val min = daily.getJSONArray("temperature_2m_min")
            val sum = daily.optJSONArray("precipitation_sum")
            val prob = daily.optJSONArray("precipitation_probability_max")
            val rise = daily.optJSONArray("sunrise")
            val set = daily.optJSONArray("sunset")
            for (i in 0 until t.length()) {
                if (max.isNull(i) || min.isNull(i)) continue
                days += DayPoint(
                    day(t.getString(i)), code?.optInt(i) ?: 0, max.getDouble(i), min.getDouble(i), sum?.optDouble(i, 0.0) ?: 0.0,
                    prob?.takeIf { !it.isNull(i) }?.getInt(i),
                    rise?.takeIf { !it.isNull(i) }?.getString(i)?.let { at(it) },
                    set?.takeIf { !it.isNull(i) }?.getString(i)?.let { at(it) },
                )
            }
        }
        return Forecast(
            time = at(cur.getString("time")),
            tempC = cur.getDouble("temperature_2m"),
            feelsC = cur.dbl("apparent_temperature"),
            humidity = cur.int("relative_humidity_2m"),
            precipMm = cur.dbl("precipitation") ?: 0.0,
            code = cur.optInt("weather_code", 3),
            cloud = cur.optInt("cloud_cover", 50),
            windKmh = cur.dbl("wind_speed_10m"),
            windDir = cur.int("wind_direction_10m"),
            isDay = cur.optInt("is_day", 1) != 0,
            hours = hours,
            days = days,
        )
    }

    /** What is stored, without touching the network. Null if nothing was ever fetched. */
    fun load(prefs: Prefs): WeatherState? {
        val env = try { prefs.forecastJson?.let { JSONObject(it) } } catch (e: Exception) { null } ?: return null
        val forecast = env.str("raw")?.let { try { parse(it) } catch (e: Exception) { null } }
        return WeatherState(forecast, env.optLong("t"), env.dbl("lat"), env.dbl("lon"), env.str("place"), env.str("err"))
    }

    /**
     * Fetches the forecast for the configured position (at most every 15 minutes unless [force]d) and stores it.
     * A failed fetch keeps the older forecast and marks it. Blocking: background threads only.
     */
    @Synchronized
    fun refresh(ctx: Context, force: Boolean): WeatherState? {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val old = load(prefs)
        val pos = Refresher.position(ctx, prefs)
        if (pos == null) {
            store(prefs, JSONObject().put("t", now).put("err", Snapshot.ERR_LOCATION))
            return load(prefs)
        }
        val (lat, lon) = pos
        val sameSpot = old?.lat != null && old.lon != null && Geo.distanceKm(lat, lon, old.lat, old.lon) < 2.0
        if (!force && old?.forecast != null && old.error == null && sameSpot && now - old.fetchedAt < TTL_MS) return old

        return try {
            val raw = FlightApi.get(URL.format(fmt(lat), fmt(lon))).second
            parse(raw) ?: throw java.io.IOException("unexpected forecast format")
            val place = Places.name(ctx, prefs, lat, lon, now)
            store(prefs, JSONObject().put("t", now).put("lat", lat).put("lon", lon).putOpt("place", place).put("raw", raw))
            load(prefs)
        } catch (e: Exception) {
            val env = JSONObject().put("err", Snapshot.ERR_NETWORK)
            if (old != null && old.forecast != null && sameSpot) {
                // keep showing the older forecast, with its own timestamp
                prefs.forecastJson?.let { JSONObject(it) }?.let { prev ->
                    prev.put("err", Snapshot.ERR_NETWORK)
                    store(prefs, prev)
                }
            } else {
                store(prefs, env.put("t", now))
            }
            load(prefs)
        }
    }

    private fun store(prefs: Prefs, env: JSONObject) {
        prefs.forecastJson = env.toString()
    }

    private fun fmt(v: Double) = "%.2f".format(Locale.US, v)
}
