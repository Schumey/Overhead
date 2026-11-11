package de.schumey.overhead

import android.content.Context
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Turns a [Flight] into the lines shown in the widget and in the app. */
object Fmt {
    data class FlightText(
        val title: String,
        val distance: String,
        val route: String?,
        val eta: String?,
        val details: String,
    )

    fun text(ctx: Context, f: Flight, now: Long): FlightText {
        val typeName = TypeNames.name(f.type) ?: f.type
        val title = listOfNotNull(f.callsign, typeName, f.route?.airline).joinToString(" · ")

        val cardinal = ctx.resources.getStringArray(R.array.cardinals)[Geo.cardinalIndex(f.bearing)]
        val distance = "${distance(f.distanceKm)} $cardinal"

        val route = f.route?.let { r ->
            if (r.origin == null && r.destination == null) null
            else "${airport(r.origin)} → ${airport(r.destination)}"
        } ?: f.registration

        val eta = f.etaMillis?.let { ctx.getString(R.string.eta, time(it), inDuration(ctx, it - now)) }

        val trend = when {
            (f.vertRateFpm ?: 0) > 300 -> " ↑"
            (f.vertRateFpm ?: 0) < -300 -> " ↓"
            else -> ""
        }
        val details = listOfNotNull(
            f.altitudeM?.let { String.format(Locale.getDefault(), "%,d m", it) + trend },
            f.speedKmh?.let { String.format(Locale.getDefault(), "%d km/h", it) },
        ).joinToString(" · ")

        return FlightText(title, distance, route, eta, details)
    }

    /** Altitude with an apostrophe as thousands separator, as in the designs: 10'378. */
    fun thousands(n: Int): String = String.format(Locale.US, "%,d", n).replace(',', '\'')

    /** Flight identifier as passengers know it (AF274), falling back to the radio callsign. */
    fun flightNo(f: Flight): String = f.route?.flightNo ?: f.callsign

    fun km1(km: Double): String = String.format(Locale.getDefault(), "%.1f", km)

    fun distance(km: Double): String =
        if (km < 10) String.format(Locale.getDefault(), "%.1f km", km)
        else String.format(Locale.getDefault(), "%d km", Math.round(km))

    fun time(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

    private fun airport(a: Airport?): String = when {
        a == null -> "?"
        a.city.isEmpty() -> a.iata
        else -> "${a.city} (${a.iata})"
    }

    private fun inDuration(ctx: Context, deltaMs: Long): String {
        val min = Math.round(deltaMs / 60_000.0).toInt()
        return when {
            min < 1 -> ctx.getString(R.string.dur_now)
            min < 60 -> ctx.getString(R.string.dur_min, min)
            else -> ctx.getString(R.string.dur_hm, min / 60, min % 60)
        }
    }
}
