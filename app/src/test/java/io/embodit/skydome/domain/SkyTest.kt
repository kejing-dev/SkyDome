package io.embodit.skydome.domain

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The astronomy and the sky's frame against public reference values (JPL Horizons, catalogue facts). */
class SkyTest {
    private val catalog: SkyCatalog by lazy { SkyCatalog.load { name -> File("src/main/assets", name).inputStream() } }

    private fun ms(local: String, zone: String): Long = LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun sepDeg(a: Equatorial, raDeg: Double, decDeg: Double) = Math.toDegrees(Astro.separation(a, Equatorial.deg(raDeg, decDeg)))

    @Test
    fun catalogueLoads() {
        assertTrue(catalog.stars.size in 5000..5100)
        assertEquals(88, catalog.constellations.size)
        assertTrue(catalog.segments.size in 500..800)
        assertEquals(36, catalog.cities.size)
        assertEquals("beijing", catalog.cities.first().id)
        // every constellation has a figure
        assertTrue(catalog.constellations.all { it.segments.isNotEmpty() })
    }

    @Test
    fun gmstAtJ2000() {
        val t = ms("2000-01-01T12:00", "UTC")
        assertEquals(280.46061837, Math.toDegrees(Astro.gmst(t)), 1e-3)
    }

    @Test
    fun planetsMatchHorizonsWithin0_3Degrees() {
        // JPL Horizons, geocentric astrometric RA / Dec, 2026-10-20 13:30 UTC (= Beijing 21:30)
        val t = ms("2026-10-20T13:30", "UTC")
        val ref = mapOf(Body.JUPITER to (144.99021 to 14.65674), Body.MARS to (135.15648 to 18.42643), Body.VENUS to (207.79948 to -18.95171), Body.SATURN to (9.96435 to 1.34867))
        for ((b, rd) in ref) {
            val d = sepDeg(Astro.planet(b, t).eq, rd.first, rd.second)
            assertTrue("$b off by $d°", d < 0.3)
        }
    }

    @Test
    fun moonWithin1Degree() {
        val t = ms("2026-10-20T13:30", "UTC")
        val d = sepDeg(Astro.moonGeocentric(t).eq, 320.40491, -16.37742)
        assertTrue("moon off by $d°", d < 1.0)
    }

    @Test
    fun polarisSitsAtTheLatitude() {
        val polaris = catalog.stars[catalog.starIndex(11767)!!]
        for (h in listOf("2026-10-20T21:30", "2026-10-21T03:00")) {
            val alt = Astro.horizontal(polaris.eq, ms(h, "Asia/Shanghai"), 39.90, 116.41).altDeg
            assertEquals(39.9, alt, 1.0)
        }
        val sydney = Astro.horizontal(polaris.eq, ms("2026-10-20T21:30", "Australia/Sydney"), -33.87, 151.21).altDeg
        assertTrue(sydney < 0)
    }

    @Test
    fun southernCrossIsUpOverSydney() {
        val cru = catalog.constellation("Cru")!!
        val alt = Astro.horizontal(cru.centre, ms("2026-10-20T21:30", "Australia/Sydney"), -33.87, 151.21).altDeg
        assertTrue("Crux alt $alt", alt > 0)
    }

    @Test
    fun boundariesNameTheConstellation() {
        assertEquals("Ori", catalog.constellationAt(Equatorial.deg(88.79, 7.41))?.abbr) // Betelgeuse
        assertEquals("UMi", catalog.constellationAt(Equatorial.deg(37.95, 89.26))?.abbr) // Polaris
        assertEquals("CMa", catalog.constellationAt(Equatorial.deg(101.29, -16.72))?.abbr) // Sirius
        assertEquals("Leo", catalog.constellationAt(Equatorial.deg(152.09, 11.97))?.abbr) // Regulus
    }

    @Test
    fun frameInvertsAndMatchesItsRotation() {
        val f = SkyFrame(39.9, 1.234, 0.7f)
        val q = f.rotation()
        for (v in listOf(Vec3d(1.0, 0.0, 0.0), Vec3d(0.3, -0.5, 0.81).normalized(), Vec3d(0.0, 0.0, 1.0))) {
            val s = f.toStage(v)
            val r = q.rotate(v.f())
            assertTrue((s - r).length < 1e-4f)
            val back = f.toEquatorial(s)
            assertTrue((back - v).length < 1e-5)
            // altitude agrees with the vertical component
            assertEquals(f.sinAlt(v), s.y.toDouble(), 1e-5)
        }
        // the celestial pole stands due north at the latitude's altitude
        val pole = f.horizontalOf(f.toStage(Vec3d(0.0, 0.0, 1.0)))
        assertEquals(39.9, pole.altDeg, 1e-3)
        assertTrue(pole.azDeg < 1e-2 || pole.azDeg > 359.99)
    }

    @Test
    fun orionDistancesFromTheCatalogue() {
        val ori = catalog.constellation("Ori")!!
        val d = SkyFacts.depthFacts(catalog, ori)
        assertNotNull(d)
        val rigel = catalog.stars[catalog.starIndex(24436)!!]
        val betelgeuse = catalog.stars[catalog.starIndex(27989)!!]
        assertEquals(855f, rigel.ly, 5f)
        assertEquals(489f, betelgeuse.ly, 5f)
        assertTrue(d!!.farthestLy >= rigel.ly)
    }

    @Test
    fun bigDipperHardlySpreads() {
        // the Dipper's middle five are a moving group at ~80 ly: in true distance they nearly share a plane
        val hips = listOf(53910, 54061, 58001, 59774, 62956, 65378)
        val depths = hips.map { DepthScale.depth(catalog.stars[catalog.starIndex(it)!!].ly) }
        assertTrue(depths.max() - depths.min() < 0.12f)
    }

    @Test
    fun depthScaleEnds() {
        assertEquals(0f, DepthScale.depth(4.4f), 1e-6f)
        assertEquals(0.5f, DepthScale.depth(9000f), 1e-6f)
        assertEquals(0.25f, DepthScale.depth(Float.NaN), 1e-6f)
    }

    @Test
    fun dialRoundTripAndWrap() {
        for (m in listOf(0.0, 90.0, 720.0, 1439.0)) assertEquals(m, Dial.minuteOf(Dial.angleOf(m)), 1e-6)
        assertEquals(10.0, Dial.deltaMinutes(1435.0, 5.0), 1e-9)
        assertEquals(-10.0, Dial.deltaMinutes(5.0, 1435.0), 1e-9)
    }

    @Test
    fun tonightHasAtMostFourRowsAndAConstellation() {
        val t = ms("2026-10-20T21:30", "Asia/Shanghai")
        val r = SkyFacts.tonight(catalog, t, 39.9, 116.41, withBodies = true) { 10 }
        assertTrue(r.rows.size in 1..4)
        assertTrue(r.rows.any { it.pick is Pick.OfConstellation })
        assertEquals(null, r.afterDarkMs)
        val day = SkyFacts.tonight(catalog, ms("2026-10-20T14:00", "Asia/Shanghai"), 39.9, 116.41, withBodies = true) { 10 }
        assertNotNull(day.afterDarkMs)
        assertTrue(Astro.sunAltitude(day.afterDarkMs!!, 39.9, 116.41) < -11.9)
    }

    @Test
    fun arcDistanceOnAndOffTheArc() {
        val a = Equatorial.deg(0.0, 0.0).unit()
        val b = Equatorial.deg(10.0, 0.0).unit()
        assertEquals(2.0, Math.toDegrees(arcDistance(Equatorial.deg(5.0, 2.0).unit(), a, b)), 1e-6)
        assertEquals(5.0, Math.toDegrees(arcDistance(Equatorial.deg(15.0, 0.0).unit(), a, b)), 1e-6)
        assertTrue(abs(Math.toDegrees(arcDistance(Equatorial.deg(3.0, 0.0).unit(), a, b))) < 1e-6)
    }

    @Test
    fun stickTurnHasADeadZoneAndTurnsClockwiseToTheRight() {
        assertEquals(0f, StickTurn.rate(0.1f), 0f)
        assertEquals(0f, StickTurn.rate(-0.15f), 0f)
        assertEquals(-StickTurn.MAX_RAD_S, StickTurn.rate(1f), 1e-6f)
        assertEquals(StickTurn.MAX_RAD_S, StickTurn.rate(-1f), 1e-6f)
        assertTrue(abs(StickTurn.rate(0.5f)) < abs(StickTurn.rate(0.9f)))
    }
}
