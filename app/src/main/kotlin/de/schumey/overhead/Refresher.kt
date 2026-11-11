package de.schumey.overhead

import android.content.Context
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One refresh cycle: position -> aircraft nearby -> routes -> ETA -> stored snapshot. Blocking; background threads only. */
object Refresher {
    private const val TAG = "Overhead"
    private const val MAX_STORED = 60
    private const val ROUTES_BY_SLANT = 8
    private const val ROUTES_BY_DISTANCE = 10
    private const val FIELD_RADIUS_KM = 10
    private const val FIELD_MAX_ALT_M = 2500
    private const val MAX_FIELD = 24
    private const val MIN_GAP_MS = 20_000L

    @Synchronized
    fun refresh(ctx: Context, force: Boolean = true, onPartial: (() -> Unit)? = null): Snapshot {
        val prefs = Prefs(ctx)
        val old = Snapshot.fromJson(prefs.snapshotJson)
        val now = System.currentTimeMillis()
        // Several triggers can fire together (widget added, alarm, app opened): do not hit the APIs for each.
        if (!force && old != null && old.error == null && now - old.time < MIN_GAP_MS) return old

        val radius = prefs.radiusKm
        val pos = position(ctx, prefs)
            ?: return save(prefs, Snapshot(now, null, null, radius, emptyList(), Snapshot.ERR_LOCATION))
        val (lat, lon) = pos

        val nearby = try {
            FlightApi.fetchNearby(lat, lon, radius)
        } catch (e: Exception) {
            // Keep showing the last data (with its old timestamp) instead of an empty widget.
            val kept = old?.copy(error = Snapshot.ERR_NETWORK)
                ?: Snapshot(now, lat, lon, radius, emptyList(), Snapshot.ERR_NETWORK)
            return save(prefs, kept)
        }

        // All aircraft in range are kept (the radar draws them all); routes are looked up for the ones the views
        // name: the closest by distance and the closest by line of sight.
        val airborne = nearby.filter { !it.onGround }
        val bySlant = airborne.sortedBy { it.slantKm }
        val wanted = (bySlant.take(ROUTES_BY_SLANT) + airborne.sortedBy { it.distanceKm }.take(ROUTES_BY_DISTANCE)).distinctBy { it.hex }
        val enriched = withRoutes(prefs, wanted, now).associateBy { it.hex }
        val flights = bySlant.take(MAX_STORED).map { enriched[it.hex] ?: it }

        // Decoration and extras; a failure here must never cost the flights.
        val place = Places.name(ctx, prefs, lat, lon, now)
        val weather = Weather.current(prefs, lat, lon, now)
        var airport: AirportInfo? = null
        var traffic = emptyList<Flight>()
        if (Views.needsAirport(ctx)) {
            val known = Airports.cached(prefs, lat, lon, now)
            if (known != null) {
                airport = known.airport
            } else {
                // The airport lookup can take half a minute: show everything else first.
                save(prefs, Snapshot(now, lat, lon, radius, flights, null, place, weather.cloudCover, weather.precipMm))
                onPartial?.invoke()
                airport = try {
                    Airports.fetch(prefs, lat, lon, now)
                } catch (e: Exception) {
                    Log.w(TAG, "airport lookup failed", e)
                    null
                }
            }
            if (airport != null) {
                traffic = try {
                    val onField = FlightApi.fetchNearby(airport.lat, airport.lon, FIELD_RADIUS_KM)
                        .filter { it.onGround || (it.altitudeM ?: Int.MAX_VALUE) <= FIELD_MAX_ALT_M }
                        .sortedBy { it.distanceKm }.take(MAX_FIELD)
                    withRoutes(prefs, onField, now)
                } catch (e: Exception) {
                    Log.w(TAG, "airport traffic lookup failed", e)
                    emptyList()
                }
            }
        }
        return save(prefs, Snapshot(now, lat, lon, radius, flights, null, place, weather.cloudCover, weather.precipMm, airport, traffic))
    }

    private fun position(ctx: Context, prefs: Prefs): Pair<Double, Double>? {
        if (prefs.useGps) {
            LocationHelper.current(ctx)?.let { return it.latitude to it.longitude }
        }
        val lat = prefs.fixedLat
        val lon = prefs.fixedLon
        return if (lat != null && lon != null) lat to lon else null
    }

    private fun withRoutes(prefs: Prefs, flights: List<Flight>, now: Long): List<Flight> {
        val cache = RouteCache.load(prefs)
        val routes = HashMap<String, RouteInfo?>()
        val missing = flights.map { it.callsign }.distinct()
            .filter { FlightApi.looksLikeAirlineCallsign(it) }
            .filter { cs ->
                val hit = RouteCache.lookup(cache, cs, now)
                if (hit != null) routes[cs] = hit.route
                hit == null
            }

        if (missing.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(6)
            try {
                val futures = missing.map { cs -> cs to pool.submit(Callable { FlightApi.fetchRoute(cs) }) }
                for ((cs, fut) in futures) {
                    try {
                        val r = fut.get(10, TimeUnit.SECONDS)
                        routes[cs] = r
                        RouteCache.put(cache, cs, r, now)
                    } catch (e: Exception) {
                        // Network hiccup or timeout: no route this time, and nothing cached so it is retried next refresh.
                    }
                }
            } finally {
                pool.shutdownNow()
            }
            prefs.routesJson = cache.toString()
        }

        return flights.map { f ->
            val withRoute = Eta.orient(f.copy(route = routes[f.callsign]))
            withRoute.copy(etaMillis = Eta.estimate(withRoute, now))
        }
    }

    private fun save(prefs: Prefs, s: Snapshot): Snapshot {
        prefs.snapshotJson = s.toJson()
        return s
    }
}
