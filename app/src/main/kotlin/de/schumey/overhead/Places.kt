package de.schumey.overhead

import android.content.Context
import android.location.Geocoder
import org.json.JSONObject
import java.util.Locale

/**
 * Name of the place for the poster headline ("Der Himmel über Frankfurt"), from the Android geocoder.
 * Like the flight query it only gets a position rounded to about 1 km, and the answer is cached per position.
 */
object Places {
    private const val RETRY_AFTER_FAILURE_MS = 60 * 60_000L

    @Suppress("DEPRECATION")
    fun name(ctx: Context, prefs: Prefs, lat: Double, lon: Double, now: Long): String? {
        val rLat = Math.round(lat * 100) / 100.0
        val rLon = Math.round(lon * 100) / 100.0
        val key = "%.2f,%.2f".format(Locale.US, rLat, rLon)
        val cached = try { prefs.placeJson?.let { JSONObject(it) } } catch (e: Exception) { null }
        if (cached != null && cached.optString("k") == key) {
            val n = cached.optString("n")
            if (n.isNotEmpty()) return n
            if (now - cached.optLong("t") < RETRY_AFTER_FAILURE_MS) return null
        }
        val found = try {
            if (!Geocoder.isPresent()) null
            else Geocoder(ctx, Locale.getDefault()).getFromLocation(rLat, rLon, 1)?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        } catch (e: Exception) {
            null
        }
        prefs.placeJson = JSONObject().put("t", now).put("k", key).put("n", found ?: "").toString()
        return found
    }
}
