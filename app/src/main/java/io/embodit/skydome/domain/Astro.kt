package io.embodit.skydome.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Right ascension and declination, radians (J2000 for stars; of date is close enough for the bodies at 2° acceptance). */
data class Equatorial(val ra: Double, val dec: Double) {
    fun unit(): Vec3d = Vec3d(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))

    companion object {
        fun of(v: Vec3d): Equatorial {
            val n = v.normalized()
            return Equatorial(atan2(n.y, n.x).let { if (it < 0) it + 2 * PI else it }, asin(n.z.coerceIn(-1.0, 1.0)))
        }

        fun deg(raDeg: Double, decDeg: Double) = Equatorial(Math.toRadians(raDeg), Math.toRadians(decDeg))
    }
}

/** Double-precision vector for the astronomy (equatorial frame: x to RA 0, z to the north pole). */
data class Vec3d(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3d) = Vec3d(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vec3d) = Vec3d(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Double) = Vec3d(x * s, y * s, z * s)

    fun dot(o: Vec3d) = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3d) = Vec3d(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    val length: Double
        get() = sqrt(dot(this))

    fun normalized(): Vec3d = length.let { if (it < 1e-15) this else this * (1.0 / it) }

    fun f(): Vec3 = Vec3(x.toFloat(), y.toFloat(), z.toFloat())
}

/** Altitude and azimuth, degrees: azimuth from north towards east. */
data class Horizontal(val altDeg: Double, val azDeg: Double)

enum class Body(val id: String) {
    SUN("sun"),
    MOON("moon"),
    MERCURY("mercury"),
    VENUS("venus"),
    MARS("mars"),
    JUPITER("jupiter"),
    SATURN("saturn");

    val isPlanet: Boolean
        get() = this != SUN && this != MOON

    /** English name (shown next to the Chinese one on the body card). */
    val english: String
        get() = id.replaceFirstChar { it.uppercaseChar() }

    companion object {
        fun of(id: String): Body? = entries.firstOrNull { it.id == id }
    }
}

/** Where a body is for an observer at a moment. */
data class BodyState(
    val body: Body,
    val eq: Equatorial,
    /** Apparent magnitude (planets and moon; the sun gets -26.7). */
    val mag: Double,
    /** Distance from the Earth, astronomical units. */
    val distAu: Double,
    /** Moon only: illuminated fraction 0..1 and phase 0..1 (0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter). */
    val illuminated: Double = 1.0,
    val phase: Double = 0.5,
)

/**
 * Low-precision ephemerides, all public formulas ported to Kotlin:
 * sun and moon from suncalc (BSD-2, V. Agafonkin; Meeus "Astronomical Algorithms" low-precision terms) with a
 * topocentric correction of the moon, the planets from JPL "Keplerian Elements for Approximate Positions of the
 * Major Planets" (Standish, table 1, 1800 - 2050), magnitudes from Mallama & Hilton 2018. Accuracy: planets about
 * 0.2°, moon within 1° (the design accepts 2°).
 */
object Astro {
    private const val RAD = PI / 180.0
    private const val OBLIQUITY = 23.4397 * RAD
    private const val AU_KM = 149_597_870.7
    private const val EARTH_RADIUS_KM = 6378.14

    fun julianDay(epochMs: Long): Double = epochMs / 86_400_000.0 + 2440587.5

    /** Days from J2000.0. */
    fun days(epochMs: Long): Double = julianDay(epochMs) - 2451545.0

    /** Greenwich mean sidereal time, radians 0..2pi. */
    fun gmst(epochMs: Long): Double = norm((280.46061837 + 360.98564736629 * days(epochMs)) * RAD)

    /** Local sidereal time for an east longitude in degrees, radians 0..2pi. */
    fun lst(epochMs: Long, lonDeg: Double): Double = norm(gmst(epochMs) + lonDeg * RAD)

    fun norm(a: Double): Double {
        val t = a % (2 * PI)
        return if (t < 0) t + 2 * PI else t
    }

    private fun eclipticToEquatorial(l: Double, b: Double): Equatorial {
        val ra = atan2(sin(l) * cos(OBLIQUITY) - tan(b) * sin(OBLIQUITY), cos(l))
        val dec = asin(sin(b) * cos(OBLIQUITY) + cos(b) * sin(OBLIQUITY) * sin(l))
        return Equatorial(norm(ra), dec)
    }

    // ---------------------------------------------------------------- sun and moon (suncalc)

    fun sun(epochMs: Long): Equatorial {
        val d = days(epochMs)
        val m = (357.5291 + 0.98560028 * d) * RAD
        val c = (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)) * RAD
        val l = m + c + 102.9372 * RAD + PI
        return eclipticToEquatorial(l, 0.0)
    }

    internal data class MoonGeo(val eq: Equatorial, val distKm: Double)

    internal fun moonGeocentric(epochMs: Long): MoonGeo {
        val d = days(epochMs)
        val l0 = (218.316 + 13.176396 * d) * RAD
        val m = (134.963 + 13.064993 * d) * RAD
        val f = (93.272 + 13.229350 * d) * RAD
        val l = l0 + 6.289 * RAD * sin(m)
        val b = 5.128 * RAD * sin(f)
        return MoonGeo(eclipticToEquatorial(l, b), 385001.0 - 20905.0 * cos(m))
    }

    /** Topocentric moon: the observer sits on the Earth's surface, not at its centre (up to ~1° shift). */
    fun moon(epochMs: Long, latDeg: Double, lonDeg: Double): Pair<Equatorial, Double> {
        val g = moonGeocentric(epochMs)
        val lst = lst(epochMs, lonDeg)
        val lat = latDeg * RAD
        val observer = Vec3d(cos(lat) * cos(lst), cos(lat) * sin(lst), sin(lat))
        val moon = g.eq.unit() * (g.distKm / EARTH_RADIUS_KM)
        return Equatorial.of(moon - observer) to g.distKm
    }

    /** Illuminated fraction and phase 0..1 of the moon (suncalc getMoonIllumination). */
    fun moonIllumination(epochMs: Long): Pair<Double, Double> {
        val s = sun(epochMs)
        val m = moonGeocentric(epochMs)
        val sdist = AU_KM
        val phi = acos((sin(s.dec) * sin(m.eq.dec) + cos(s.dec) * cos(m.eq.dec) * cos(s.ra - m.eq.ra)).coerceIn(-1.0, 1.0))
        val inc = atan2(sdist * sin(phi), m.distKm - sdist * cos(phi))
        val angle = atan2(cos(s.dec) * sin(s.ra - m.eq.ra), sin(s.dec) * cos(m.eq.dec) - cos(s.dec) * sin(m.eq.dec) * cos(s.ra - m.eq.ra))
        val fraction = (1 + cos(inc)) / 2
        val phase = 0.5 + 0.5 * inc * (if (angle < 0) -1.0 else 1.0) / PI
        return fraction to phase
    }

    // ---------------------------------------------------------------- planets (JPL approximate elements)

    /** a (au), e, I, L, long. perihelion, long. node (degrees) and their rates per Julian century. */
    private class Elements(val el: DoubleArray, val rate: DoubleArray)

    private val ELEMENTS =
        mapOf(
            Body.MERCURY to Elements(doubleArrayOf(0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593), doubleArrayOf(0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081)),
            Body.VENUS to Elements(doubleArrayOf(0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255), doubleArrayOf(0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418)),
            null to Elements(doubleArrayOf(1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0), doubleArrayOf(0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0)),
            Body.MARS to Elements(doubleArrayOf(1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891), doubleArrayOf(0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343)),
            Body.JUPITER to Elements(doubleArrayOf(5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909), doubleArrayOf(-0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106)),
            Body.SATURN to Elements(doubleArrayOf(9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448), doubleArrayOf(-0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794)),
        )

    /** Heliocentric ecliptic J2000 position, au; null = the Earth-Moon barycentre. */
    private fun heliocentric(body: Body?, epochMs: Long): Vec3d {
        val e = ELEMENTS.getValue(body)
        val t = days(epochMs) / 36525.0
        val v = DoubleArray(6) { e.el[it] + e.rate[it] * t }
        val a = v[0]
        val ecc = v[1]
        val inc = v[2] * RAD
        val l = v[3] * RAD
        val peri = v[4] * RAD
        val node = v[5] * RAD
        val omega = peri - node
        var m = norm(l - peri)
        if (m > PI) m -= 2 * PI
        var ea = m + ecc * sin(m)
        repeat(8) { ea -= (ea - ecc * sin(ea) - m) / (1 - ecc * cos(ea)) }
        val xp = a * (cos(ea) - ecc)
        val yp = a * sqrt(1 - ecc * ecc) * sin(ea)
        val co = cos(omega)
        val so = sin(omega)
        val cn = cos(node)
        val sn = sin(node)
        val ci = cos(inc)
        val si = sin(inc)
        return Vec3d(
            (co * cn - so * sn * ci) * xp + (-so * cn - co * sn * ci) * yp,
            (co * sn + so * cn * ci) * xp + (-so * sn + co * cn * ci) * yp,
            (so * si) * xp + (co * si) * yp,
        )
    }

    private fun eclipticVecToEquatorial(v: Vec3d): Vec3d {
        val e = 23.43928 * RAD
        return Vec3d(v.x, cos(e) * v.y - sin(e) * v.z, sin(e) * v.y + cos(e) * v.z)
    }

    fun planet(body: Body, epochMs: Long): BodyState {
        val p = heliocentric(body, epochMs)
        val earth = heliocentric(null, epochMs)
        val geo = p - earth
        val r = p.length
        val delta = geo.length
        val bigR = earth.length
        val cosAlpha = ((r * r + delta * delta - bigR * bigR) / (2 * r * delta)).coerceIn(-1.0, 1.0)
        val alpha = Math.toDegrees(acos(cosAlpha))
        return BodyState(body, Equatorial.of(eclipticVecToEquatorial(geo)), magnitude(body, r, delta, alpha), delta)
    }

    /** Mallama & Hilton (2018) main terms; Saturn without the ring-tilt term (rings near edge-on in 2025 - 2027). */
    fun magnitude(body: Body, r: Double, delta: Double, a: Double): Double {
        val base = 5 * log10(r * delta)
        return when (body) {
            Body.MERCURY -> -0.613 + base + 6.328e-2 * a - 1.6336e-3 * a * a + 3.3644e-5 * a * a * a - 3.4265e-7 * a * a * a * a + 1.6893e-9 * a * a * a * a * a - 3.0334e-12 * a * a * a * a * a * a
            Body.VENUS -> -4.384 + base - 1.044e-3 * a + 3.687e-4 * a * a - 2.814e-6 * a * a * a + 8.938e-9 * a * a * a * a
            Body.MARS -> -1.601 + base + 2.267e-2 * a - 1.302e-4 * a * a
            Body.JUPITER -> -9.395 + base - 3.7e-4 * a + 6.16e-4 * a * a
            Body.SATURN -> -8.914 + base + 0.026 * a
            else -> 0.0
        }
    }

    /** Every body for an observer: sun, moon (topocentric) and the five naked-eye planets. */
    fun bodies(epochMs: Long, latDeg: Double, lonDeg: Double): List<BodyState> {
        val out = ArrayList<BodyState>(7)
        out += BodyState(Body.SUN, sun(epochMs), -26.7, heliocentric(null, epochMs).length)
        val (moonEq, distKm) = moon(epochMs, latDeg, lonDeg)
        val (fraction, phase) = moonIllumination(epochMs)
        // full moon -12.7; fainter with the phase angle (Allen's approximation)
        val phaseAngle = Math.toDegrees(acos((2 * fraction - 1).coerceIn(-1.0, 1.0)))
        out += BodyState(Body.MOON, moonEq, -12.73 + 0.026 * abs(phaseAngle) + 4e-9 * phaseAngle * phaseAngle * phaseAngle * phaseAngle, distKm / AU_KM, fraction, phase)
        for (b in listOf(Body.MERCURY, Body.VENUS, Body.MARS, Body.JUPITER, Body.SATURN)) out += planet(b, epochMs)
        return out
    }

    // ---------------------------------------------------------------- horizon

    /** Altitude / azimuth of [eq] for an observer. */
    fun horizontal(eq: Equatorial, epochMs: Long, latDeg: Double, lonDeg: Double): Horizontal = horizontalAt(eq, lst(epochMs, lonDeg), latDeg)

    fun horizontalAt(eq: Equatorial, lst: Double, latDeg: Double): Horizontal {
        val lat = latDeg * RAD
        val h = lst - eq.ra
        val sinAlt = sin(lat) * sin(eq.dec) + cos(lat) * cos(eq.dec) * cos(h)
        val north = cos(lat) * sin(eq.dec) - sin(lat) * cos(eq.dec) * cos(h)
        val east = -cos(eq.dec) * sin(h)
        val az = Math.toDegrees(atan2(east, north)).let { if (it < 0) it + 360 else it }
        return Horizontal(Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0))), az)
    }

    fun sunAltitude(epochMs: Long, latDeg: Double, lonDeg: Double): Double = horizontal(sun(epochMs), epochMs, latDeg, lonDeg).altDeg

    /**
     * The next moment after [fromMs] (within [withinMs]) at which [altitude] crosses [threshold] in the given
     * direction: 10-minute samples, then bisection to one minute. Null when it does not happen.
     */
    fun nextCrossing(fromMs: Long, withinMs: Long, threshold: Double, rising: Boolean, altitude: (Long) -> Double): Long? {
        val step = 10 * 60_000L
        var t0 = fromMs
        var a0 = altitude(t0) - threshold
        while (t0 < fromMs + withinMs) {
            val t1 = t0 + step
            val a1 = altitude(t1) - threshold
            val crosses = if (rising) a0 < 0 && a1 >= 0 else a0 >= 0 && a1 < 0
            if (crosses) {
                var lo = t0
                var hi = t1
                while (hi - lo > 60_000L) {
                    val mid = (lo + hi) / 2
                    val am = altitude(mid) - threshold
                    val beforeSide = if (rising) am < 0 else am >= 0
                    if (beforeSide) lo = mid else hi = mid
                }
                return hi
            }
            t0 = t1
            a0 = a1
        }
        return null
    }

    /** Altitude of the horizon crossing used for rise and set: refraction and the disc. */
    fun riseThreshold(body: Body): Double =
        when (body) {
            Body.SUN -> -0.833
            Body.MOON -> 0.125
            else -> -0.567
        }

    fun bodyAltitude(body: Body, epochMs: Long, latDeg: Double, lonDeg: Double): Double {
        val eq =
            when (body) {
                Body.SUN -> sun(epochMs)
                Body.MOON -> moon(epochMs, latDeg, lonDeg).first
                else -> planet(body, epochMs).eq
            }
        return horizontal(eq, epochMs, latDeg, lonDeg).altDeg
    }

    // ---------------------------------------------------------------- precession (IAU 1976) for the boundaries

    /** J2000 -> mean equator and equinox of a Julian epoch year (the boundary table is for 1875.0). */
    fun precessFromJ2000(eq: Equatorial, epochYear: Double): Equatorial {
        val t = (epochYear - 2000.0) / 100.0
        val arc = RAD / 3600.0
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * arc
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * arc
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * arc
        val a = cos(eq.dec) * sin(eq.ra + zeta)
        val b = cos(theta) * cos(eq.dec) * cos(eq.ra + zeta) - sin(theta) * sin(eq.dec)
        val c = sin(theta) * cos(eq.dec) * cos(eq.ra + zeta) + cos(theta) * sin(eq.dec)
        return Equatorial(norm(atan2(a, b) + z), asin(c.coerceIn(-1.0, 1.0)))
    }

    /** Angular distance, radians. */
    fun separation(a: Equatorial, b: Equatorial): Double = acos(a.unit().dot(b.unit()).coerceIn(-1.0, 1.0))
}
