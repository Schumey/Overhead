package de.schumey.overhead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    // Trimmed from a real adsb.lol response (point 50.03/8.57): one airliner, one helicopter, one on the ground, one without position.
    private val nearby = """
        {"ac":[
          {"hex":"800739","flight":"AIC54U  ","r":"VT-ANS","t":"B788","alt_baro":37000,"gs":507.5,"track":108.61,
           "baro_rate":160,"lat":50.142672,"lon":8.220743},
          {"hex":"3de586","flight":"IBIS3   ","r":"D-HHEC","t":"EC45","alt_baro":1775,"gs":79.1,"track":212.08,
           "baro_rate":-384,"lat":50.011162,"lon":8.681129},
          {"hex":"3c6444","flight":"DLH400  ","t":"A388","alt_baro":"ground","gs":12.0,"lat":50.04,"lon":8.56},
          {"hex":"aabbcc","flight":"NOPOS1  ","alt_baro":30000}
        ],"total":4}
    """.trimIndent()

    private val route = """
        {"response":{"flightroute":{"callsign":"AFR274","airline":{"name":"Air France"},
          "origin":{"iata_code":"CDG","icao_code":"LFPG","municipality":"Paris","latitude":49.0128,"longitude":2.55},
          "destination":{"iata_code":"HND","icao_code":"RJTT","municipality":"Tokyo","latitude":35.5523,"longitude":139.78}}}}
    """.trimIndent()

    @Test
    fun distanceFrankfurtToParis() {
        // FRA -> CDG is about 450 km.
        val d = Geo.distanceKm(50.0333, 8.5706, 49.0128, 2.55)
        assertEquals(450.0, d, 15.0)
    }

    @Test
    fun bearingAndCardinals() {
        assertEquals(90.0, Geo.bearing(0.0, 0.0, 0.0, 10.0), 0.01)
        assertEquals(0, Geo.cardinalIndex(0.0))
        assertEquals(0, Geo.cardinalIndex(359.0))
        assertEquals(2, Geo.cardinalIndex(95.0))
        assertEquals(7, Geo.cardinalIndex(320.0))
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 0.001)
    }

    @Test
    fun parsesNearbyAircraft() {
        val list = FlightApi.parseNearby(nearby, 50.03, 8.57)
        assertEquals(3, list.size) // the one without a position is dropped
        val a = list[0]
        assertEquals("AIC54U", a.callsign)
        assertEquals("B788", a.type)
        assertEquals(11278, a.altitudeM) // 37000 ft
        assertEquals(940, a.speedKmh) // 507.5 kt
        assertEquals(160, a.vertRateFpm)
        // adsb.lol itself reported dst=15.12 nm for this aircraft: 15.12 * 1.852 = 28.0 km.
        assertEquals(28.0, a.distanceKm, 0.5)
        assertTrue(a.slantKm > a.distanceKm)
        assertFalse(a.onGround)
        assertTrue(list[2].onGround)
        assertNull(list[2].altitudeM)
    }

    @Test
    fun parsesRoute() {
        val r = FlightApi.parseRoute(route)!!
        assertEquals("Air France", r.airline)
        assertEquals("CDG", r.origin!!.iata)
        assertEquals("Tokyo", r.destination!!.city)
        assertNull(FlightApi.parseRoute("""{"response":"unknown callsign"}"""))
    }

    @Test
    fun callsignFilter() {
        assertTrue(FlightApi.looksLikeAirlineCallsign("DLH400"))
        assertTrue(FlightApi.looksLikeAirlineCallsign("EZY29JH"))
        assertFalse(FlightApi.looksLikeAirlineCallsign("D-HHEC"))
        assertFalse(FlightApi.looksLikeAirlineCallsign("N123AB"))
    }

    private fun flight(lat: Double, lon: Double, track: Double?, speed: Int?, dest: Airport?, ground: Boolean = false) = Flight(
        "X", "ABC1", null, null, 10000, ground, speed, track, 0, lat, lon, 5.0, 0.0, 10.0,
        route = RouteInfo(null, null, dest),
    )

    @Test
    fun etaFromDistanceAndSpeed() {
        // 1000 km east of a destination at 900 km/h, flying straight at it -> about 67 minutes.
        val dest = Airport("XXX", "Dest", 0.0, 0.0)
        val lonStart = Math.toDegrees(1000.0 / 6371.0088) // 1000 km along the equator
        val f = flight(0.0, lonStart, 270.0, 900, dest)
        val eta = Eta.estimate(f, 0L)!!
        assertEquals(66.7, eta / 60_000.0, 0.5)
    }

    @Test
    fun noEtaWhenFlyingAwayOrUnknown() {
        val dest = Airport("XXX", "Dest", 0.0, 0.0)
        val lonStart = Math.toDegrees(1000.0 / 6371.0088)
        assertNull(Eta.estimate(flight(0.0, lonStart, 90.0, 900, dest), 0L)) // heading away from the destination
        assertNull(Eta.estimate(flight(0.0, lonStart, 270.0, 100, dest), 0L)) // too slow to be cruising
        assertNull(Eta.estimate(flight(0.0, lonStart, 270.0, null, dest), 0L))
        assertNull(Eta.estimate(flight(0.0, lonStart, 270.0, 900, null), 0L))
        assertNull(Eta.estimate(flight(0.0, lonStart, 270.0, 900, dest, ground = true), 0L))
    }

    @Test
    fun snapshotRoundTrip() {
        val f = FlightApi.parseNearby(nearby, 50.03, 8.57)[0].let {
            it.copy(route = FlightApi.parseRoute(route), etaMillis = 123456789L)
        }
        val s = Snapshot(42L, 50.03, 8.57, 25, listOf(f), Snapshot.ERR_NETWORK)
        val back = Snapshot.fromJson(s.toJson())!!
        assertEquals(s, back)
        assertNull(Snapshot.fromJson("not json"))
        assertNotNull(Snapshot.fromJson("""{"time":1,"flights":[]}"""))
    }

    @Test
    fun routeCacheExpiry() {
        val store = org.json.JSONObject()
        RouteCache.put(store, "ABC1", null, 0L)
        assertNotNull(RouteCache.lookup(store, "ABC1", 10 * 60_000L)) // known-missing stays for a while
        assertNull(RouteCache.lookup(store, "ABC1", 31 * 60_000L)) // then it is asked again
        assertNull(RouteCache.lookup(store, "ZZZ9", 0L))
        RouteCache.put(store, "DEF2", FlightApi.parseRoute(route), 0L)
        assertEquals("HND", RouteCache.lookup(store, "DEF2", 3_600_000L)!!.route!!.destination!!.iata)
    }
}
