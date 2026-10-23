package io.embodit.skydome.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sky's frame in the room. The celestial sphere entity's local axes are the equatorial frame (x to RA 0,
 * z to the celestial north pole); its rotation takes them into Stage space for an observer at latitude
 * [latDeg], local sidereal time [lst] (radians), with true north at Stage yaw [northYaw] (see [Yaw]).
 */
class SkyFrame(val latDeg: Double, val lst: Double, val northYaw: Float) {
    private val lat = Math.toRadians(latDeg)
    private val sl = sin(lat)
    private val cl = sin(PI / 2 - lat)
    private val sL = sin(lst)
    private val cL = cos(lst)
    private val north = Yaw.dir(northYaw)
    private val east = Yaw.dir(northYaw - (PI / 2).toFloat())

    /** Stage direction of a local (north, east, up) triple. */
    fun local(n: Double, e: Double, u: Double): Vec3 = north * n.toFloat() + east * e.toFloat() + Vec3.UP * u.toFloat()

    /** Sine of the altitude of an equatorial unit vector. */
    fun sinAlt(v: Vec3d): Double = sl * v.z + cl * (v.x * cL + v.y * sL)

    /** Stage direction of an equatorial unit vector. */
    fun toStage(v: Vec3d): Vec3 {
        val p = v.x * cL + v.y * sL
        val n = cl * v.z - sl * p
        val e = -(v.x * sL - v.y * cL)
        val u = sl * v.z + cl * p
        return local(n, e, u)
    }

    /** Horizontal coordinates of a Stage direction. */
    fun horizontalOf(d: Vec3): Horizontal {
        val u = d.normalized()
        val n = u.dot(north).toDouble()
        val e = u.dot(east).toDouble()
        val alt = Math.toDegrees(asin(u.y.toDouble().coerceIn(-1.0, 1.0)))
        val az = Math.toDegrees(atan2(e, n)).let { if (it < 0) it + 360 else it }
        return Horizontal(alt, az)
    }

    /** Equatorial unit vector of a Stage direction (inverse of [toStage]). */
    fun toEquatorial(d: Vec3): Vec3d {
        val u = d.normalized()
        val n = u.dot(north).toDouble()
        val e = u.dot(east).toDouble()
        val up = u.y.toDouble()
        // invert: p = cl*up - sl*n ; z = sl*up + cl*n ; x*sL - y*cL = -e
        val z = sl * up + cl * n
        val p = cl * up - sl * n
        val x = p * cL - e * sL
        val y = p * sL + e * cL
        return Vec3d(x, y, z)
    }

    /** Rotation of the celestial sphere entity (local equatorial axes -> Stage). */
    fun rotation(): Quat4 = Quat4.fromBasis(toStage(Vec3d(1.0, 0.0, 0.0)), toStage(Vec3d(0.0, 1.0, 0.0)), toStage(Vec3d(0.0, 0.0, 1.0)))
}

/** Eight-point compass words. */
object Compass {
    private val ZH = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    private val EN = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun index(azDeg: Double): Int = (((azDeg % 360 + 360) % 360 + 22.5) / 45.0).toInt() % 8

    fun word(azDeg: Double, zh: Boolean): String = if (zh) ZH[index(azDeg)] else EN[index(azDeg)]
}

/** The fixed logarithmic depth scale of "true distance" (design `spatialLayout.pulled.scale`). */
object DepthScale {
    const val NEAR_LY = 10f
    const val FAR_LY = 5000f
    const val DEPTH_M = 0.5f
    const val UNKNOWN_M = 0.25f

    /** Metres behind the front plane for a distance in light years (NaN -> the middle plane). */
    fun depth(ly: Float): Float {
        if (ly.isNaN()) return UNKNOWN_M
        val c = ly.coerceIn(NEAR_LY, FAR_LY)
        return DEPTH_M * (log10(c) - log10(NEAR_LY)) / (log10(FAR_LY) - log10(NEAR_LY))
    }
}

/** One star of a pulled-down constellation model, model metres: x right, y up, z away from the viewer. */
data class ModelStar(val starIndex: Int, val x: Float, val y: Float, val depth: Float, val size: Float)

/**
 * Lays a constellation out flat as seen in the sky (gnomonic projection about its centre: east to the left,
 * north up), scaled so that its longest side is [longestM].
 */
object PulledLayout {
    fun layout(catalog: SkyCatalog, c: Constellation, longestM: Float = 0.5f): List<ModelStar> {
        val centre = c.centre
        val c0 = centre.unit()
        val eastAxis = Vec3d(0.0, 0.0, 1.0).cross(c0).normalized()
        val northAxis = c0.cross(eastAxis).normalized()
        val raw =
            c.stars.map { i ->
                val u = catalog.stars[i].unit
                val k = 1.0 / max(1e-6, u.dot(c0))
                Triple(i, -(u.dot(eastAxis) * k), u.dot(northAxis) * k)
            }
        val minX = raw.minOf { it.second }
        val maxX = raw.maxOf { it.second }
        val minY = raw.minOf { it.third }
        val maxY = raw.maxOf { it.third }
        val span = max(max(maxX - minX, maxY - minY), 1e-6)
        val s = longestM / span
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        return raw.map { (i, x, y) ->
            val star = catalog.stars[i]
            ModelStar(i, ((x - cx) * s).toFloat(), ((y - cy) * s).toFloat(), DepthScale.depth(star.ly), modelStarSize(star.vmag))
        }
    }

    /** Angular size of the layout in the sky (radians of its longest side): the flight starts that big. */
    fun angularSpan(catalog: SkyCatalog, c: Constellation): Double {
        var best = 0.0
        val list = c.stars.toList()
        for (i in list.indices) for (j in i + 1 until list.size) best = max(best, acos(catalog.stars[list[i]].unit.dot(catalog.stars[list[j]].unit).coerceIn(-1.0, 1.0)))
        return best
    }

    /** Diameter of a model star: 0.008 - 0.025 m by magnitude. */
    fun modelStarSize(vmag: Float): Float = (0.025f - (vmag.coerceIn(0f, 5f) / 5f) * 0.017f)
}

/** Sky star sprites: size and opacity by magnitude, colour class by B-V (design entities3d.stars). */
object StarLook {
    const val COLOR_CLASSES = 6
    const val ALPHA_LEVELS = 4

    fun colorClass(bv: Float): Int =
        when {
            bv.isNaN() -> 2
            bv < -0.1f -> 0
            bv < 0.15f -> 1
            bv < 0.45f -> 2
            bv < 0.8f -> 3
            bv < 1.25f -> 4
            else -> 5
        }

    /** Opacity level 0..3: 6 mag 25 % ... 1 mag and brighter 100 %. */
    fun alphaLevel(vmag: Float): Int =
        when {
            vmag <= 1.5f -> 3
            vmag <= 3.0f -> 2
            vmag <= 4.5f -> 1
            else -> 0
        }

    fun alphaOf(level: Int): Float = floatArrayOf(0.30f, 0.55f, 0.8f, 1f)[level]

    /** Sprite edge, metres on the 5.9 m sphere: 0.024 - 0.11 (the bright core is about a third of it: 0.08° - 0.35°). */
    fun size(vmag: Float): Float {
        val t = ((6f - vmag.coerceIn(-1.5f, 6f)) / 7.5f).coerceIn(0f, 1f)
        return 0.024f + t * sqrt(t) * 0.085f
    }
}

/** The 24-hour dial: one turn is 24 hours; noon at the top, midnight at the bottom. */
object Dial {
    const val SNAP_MIN = 5
    const val NOW_SNAP_MIN = 10

    /** Dial angle (radians, counter-clockwise from the top, as seen by the user) of a minute of the day. */
    fun angleOf(minuteOfDay: Double): Double = -((minuteOfDay - 720.0) / 1440.0) * 2 * PI

    fun minuteOf(angle: Double): Double {
        val m = 720.0 - angle / (2 * PI) * 1440.0
        return ((m % 1440.0) + 1440.0) % 1440.0
    }

    /** Signed shortest difference b - a in minutes of a day (-720..720). */
    fun deltaMinutes(a: Double, b: Double): Double {
        var d = (b - a) % 1440.0
        if (d > 720) d -= 1440.0
        if (d < -720) d += 1440.0
        return d
    }

    fun snap(minutes: Double): Double = Math.round(minutes / SNAP_MIN) * SNAP_MIN.toDouble()
}

/** Angular distance from a direction to the great-circle arc a-b, radians. */
fun arcDistance(p: Vec3d, a: Vec3d, b: Vec3d): Double {
    val n = a.cross(b)
    val nl = n.length
    if (nl < 1e-12) return acos(p.dot(a).coerceIn(-1.0, 1.0))
    val nn = n * (1.0 / nl)
    val proj = (p - nn * p.dot(nn)).normalized()
    // the projection lies on the arc when it is between a and b
    val onArc = a.cross(proj).dot(nn) >= 0 && proj.cross(b).dot(nn) >= 0
    return if (onArc) abs(asin(p.dot(nn).coerceIn(-1.0, 1.0))) else min(acos(p.dot(a).coerceIn(-1.0, 1.0)), acos(p.dot(b).coerceIn(-1.0, 1.0)))
}

/**
 * Thumbstick turn of the pulled-down model (taste profile D1): dead zone 0.15, then a quadratic ramp to 60° per
 * second; right turns clockwise seen from above (negative yaw, as [Yaw] counts + to the left).
 */
object StickTurn {
    const val DEAD_ZONE = 0.15f
    const val MAX_RAD_S = 1.0471976f
    const val SMOOTH_S = 0.12f

    fun rate(x: Float): Float {
        val m = abs(x)
        if (m <= DEAD_ZONE) return 0f
        val t = ((m - DEAD_ZONE) / (1f - DEAD_ZONE)).coerceIn(0f, 1f)
        return -kotlin.math.sign(x) * t * t * MAX_RAD_S
    }
}
