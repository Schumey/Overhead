package de.schumey.overhead

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One refresh cycle: position -> aircraft nearby -> routes -> ETA -> stored snapshot. Blocking; background threads only. */
object Refresher {
    private const val MAX_FLIGHTS = 8
    private const val MIN_GAP_MS = 20_000L

    @Synchronized
    fun refresh(ctx: Context, force: Boolean = true): Snapshot {
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

        val top = nearby.filter { !it.onGround }.sortedBy { it.slantKm }.take(MAX_FLIGHTS)
        val flights = withRoutes(prefs, top, now)
        return save(prefs, Snapshot(now, lat, lon, radius, flights, null))
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
            val pool = Executors.newFixedThreadPool(4)
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
            val withRoute = f.copy(route = routes[f.callsign])
            withRoute.copy(etaMillis = Eta.estimate(withRoute, now))
        }
    }

    private fun save(prefs: Prefs, s: Snapshot): Snapshot {
        prefs.snapshotJson = s.toJson()
        return s
    }
}
