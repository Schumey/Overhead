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
        {"response":{"flightroute":{"callsign":"AFR274","callsign_iata":"AF274","airline":{"name":"Air France","icao":"AFR"},
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
        assertEquals("AF274", r.flightNo)
        assertEquals("AFR", r.airlineIcao)
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
    fun routeIsSwappedWhenFlownTheOtherWay() {
        val berlin = Airport("BER", "Berlin", 52.36, 13.5)
        val cologne = Airport("CGN", "Cologne", 50.87, 7.14)
        val stored = RouteInfo(null, berlin, cologne) // database says Berlin -> Cologne
        // Over Arnsberg-ish (51.4N 8.0E), flying east (track 90) = toward Berlin: the real leg is Cologne -> Berlin.
        val east = flight(51.4, 8.0, 90.0, 600, null).copy(route = stored)
        val fixedEast = Eta.orient(east).route!!
        assertEquals("CGN", fixedEast.origin!!.iata)
        assertEquals("BER", fixedEast.destination!!.iata)
        // Flying west (track 270) = toward Cologne: as stored, and it gets an ETA.
        val west = flight(51.4, 8.0, 270.0, 600, null).copy(route = stored)
        assertEquals(west, Eta.orient(west))
        assertNotNull(Eta.estimate(west, 0L))
        // After the swap the eastbound aircraft has an ETA too (before, it had none because it flew away).
        assertNull(Eta.estimate(east, 0L))
        assertNotNull(Eta.estimate(Eta.orient(east), 0L))
    }

    // A made-up airport on the equator-ish grid: one east-west runway of about 7 km through the reference point.
    private val here = AirportInfo(
        "EDXX", "XXX", "Testfield", 50.0, 8.55,
        listOf(Runway("07/25", 50.0, 8.50, 50.0, 8.60)),
    )

    private fun onField(lat: Double, lon: Double, ground: Boolean, kmh: Int, alt: Int? = null, rate: Int? = 0, origin: String? = null, dest: String? = null): Flight {
        val route = if (origin != null || dest != null) {
            RouteInfo(null, origin?.let { Airport(it, it, 0.0, 0.0) }, dest?.let { Airport(it, it, 0.0, 0.0) })
        } else null
        return flight(lat, lon, 90.0, kmh, null, ground).copy(altitudeM = alt, vertRateFpm = rate, route = route)
    }

    @Test
    fun classifiesTrafficOnTheField() {
        val takeoff = onField(50.0, 8.55, true, 150, origin = "XXX", dest = "YYY") // on the runway, fast, leaving from here
        val landed = onField(50.0, 8.57, true, 120, origin = "YYY", dest = "XXX") // on the runway, fast, arriving here
        val taxi = onField(50.01, 8.53, true, 25) // off the runway, rolling
        val stand = onField(50.012, 8.54, true, 0) // parked
        val departed = onField(50.0, 8.62, false, 330, alt = 400, rate = 1800, origin = "XXX") // just lifted off
        val overflight = onField(50.0, 8.55, false, 800, alt = 10000, rate = 0) // far above: not part of the picture
        val result = Airfield.classify(here, listOf(stand, taxi, takeoff, overflight, departed, landed))

        assertEquals(5, result.size)
        val byStatus = result.associate { it.flight to it.status }
        assertEquals(FieldStatus.TAKEOFF, byStatus[takeoff])
        assertEquals(FieldStatus.LANDED, byStatus[landed])
        assertEquals(FieldStatus.TAXI, byStatus[taxi])
        assertEquals(FieldStatus.STAND, byStatus[stand])
        assertEquals(FieldStatus.DEPARTED, byStatus[departed])
        // Priority order: takeoff first, parked last.
        assertEquals(FieldStatus.TAKEOFF, result.first().status)
        assertEquals(FieldStatus.STAND, result.last().status)
        // Runway position: the middle of the runway is about halfway along.
        assertEquals(0.5, result.first { it.status == FieldStatus.TAKEOFF }.along, 0.05)
        assertEquals(-1, result.first { it.status == FieldStatus.TAXI }.runway)
        assertEquals(1, Airfield.beforeTakeoff(here, result)) // only the one leaving from here
        assertEquals(1, Airfield.justDeparted(result))
    }

    @Test
    fun parsesAirportsFromOpenStreetMap() {
        val aerodromes = """
            {"elements":[
              {"type":"node","lat":50.2,"lon":8.7,"tags":{"icao":"EDFE","name":"Flugplatz Egelsbach"}},
              {"type":"way","center":{"lat":50.033,"lon":8.570},"tags":{"icao":"EDDF","iata":"FRA","name":"Frankfurt Airport","name:de":"Flughafen Frankfurt Main"}},
              {"type":"node","lat":50.5,"lon":8.0,"tags":{"name":"no code, ignored"}}
            ]}
        """.trimIndent()
        val a = Airports.parseAerodromes(aerodromes, 50.1, 8.6)!!
        assertEquals("EDDF", a.icao) // the one with scheduled service wins over the nearer airstrip
        assertEquals("FRA", a.iata)
        assertEquals("Flughafen Frankfurt Main", a.name)
        // Only an airstrip: used when it is close, ignored when far.
        val strip = """{"elements":[{"type":"node","lat":50.2,"lon":8.7,"tags":{"icao":"EDFE"}}]}"""
        assertEquals("EDFE", Airports.parseAerodromes(strip, 50.19, 8.69)!!.icao)
        assertNull(Airports.parseAerodromes(strip, 51.5, 9.5))

        val runways = Airports.parseRunways("""
            {"elements":[{"type":"way","tags":{"ref":"07C/25C"},"geometry":[{"lat":50.03,"lon":8.53},{"lat":50.03,"lon":8.55},{"lat":50.04,"lon":8.60}]}]}
        """.trimIndent())
        assertEquals(1, runways.size)
        assertEquals("07C/25C", runways[0].ref)
        assertEquals(8.60, runways[0].lon2, 0.0001)
    }

    @Test
    fun snapshotKeepsAirportData() {
        val s = Snapshot(1L, 50.0, 8.5, 25, emptyList(), null, airport = here, airportTraffic = listOf(onField(50.0, 8.55, true, 150)))
        assertEquals(s, Snapshot.fromJson(s.toJson()))
    }

    @Test
    fun sunAndMoon() {
        fun ms(iso: String) = java.time.Instant.parse(iso).toEpochMilli()
        // Frankfurt around solar noon at midsummer: 90 - 50.03 + 23.44 = 63.4 degrees.
        assertEquals(63.4, Sky.sunElevationDeg(50.03, 8.57, ms("2026-06-21T11:26:00Z")), 1.0)
        assertTrue(Sky.sunElevationDeg(50.03, 8.57, ms("2026-11-10T22:30:00Z")) < -20)
        // The new moon was on 2026-11-09 (about 22:00 UTC); the evening after, it is a thin waxing crescent.
        assertEquals(0.04, Sky.moonPhase(ms("2026-11-10T22:38:00Z")), 0.03)
        // A full moon about two weeks later (2026-11-24).
        assertEquals(0.5, Sky.moonPhase(ms("2026-11-24T14:00:00Z")), 0.04)
    }

    @Test
    fun weatherParsing() {
        val w = Weather.parse("""{"current":{"time":"2026-11-10T22:30","cloud_cover":100,"precipitation":0.40}}""")
        assertEquals(100, w.cloudCover)
        assertEquals(0.4, w.precipMm!!, 0.001)
        assertNull(Weather.parse("""{"error":true}""").cloudCover)
    }

    @Test
    fun liveriesAreStablePerAirline() {
        val base = FlightApi.parseNearby(nearby, 50.03, 8.57)[0]
        val dlh = base.copy(route = RouteInfo(null, null, null, "LH400", "DLH"))
        assertEquals(Liveries.forFlight(dlh).primary, Liveries.forFlight(dlh.copy(callsign = "DLH401")).primary)
        val unknown = base.copy(route = RouteInfo(null, null, null, "XX1", "ZZZ"))
        assertEquals(Liveries.forFlight(unknown).primary, Liveries.forFlight(unknown).primary)
        // General aviation (no airline callsign) gets the neutral scheme.
        assertEquals(Liveries.neutral.primary, Liveries.forFlight(base.copy(callsign = "D-HHEC", route = null)).primary)
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
