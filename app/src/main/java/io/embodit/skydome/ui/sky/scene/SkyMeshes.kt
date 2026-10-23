package io.embodit.skydome.ui.sky.scene

import io.embodit.skydome.domain.SkyCatalog
import io.embodit.skydome.domain.SkyFrame
import io.embodit.skydome.domain.StarLook
import io.embodit.skydome.domain.Vec3
import io.embodit.skydome.domain.Vec3d
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Geometry of the celestial sphere, in its local (equatorial) frame: x to RA 0, z to the north pole, metres.
 * Only what is above the horizon is built (design needsCheck N1, plan 2: the CPU picks the visible stars and
 * line pieces whenever the time moves; the mesh is refilled, not recreated).
 */
internal object SkyGeometry {
    const val STAR_R = 5.90
    const val LINE_R = 5.85
    const val LIT_R = 5.82
    const val BODY_R = 5.80
    const val LINE_W = 0.012f
    const val LIT_W = LINE_W * 1.6f

    /** Sine of the lowest altitude still drawn: just under the horizon; the glow band hides the cut. */
    private val MIN_SIN_ALT = sin(Math.toRadians(-0.3))

    private fun tangents(u: Vec3d): Pair<Vec3d, Vec3d> {
        val ref = if (kotlin.math.abs(u.z) > 0.99) Vec3d(1.0, 0.0, 0.0) else Vec3d(0.0, 0.0, 1.0)
        val t1 = ref.cross(u).normalized()
        val t2 = u.cross(t1).normalized()
        return t1 to t2
    }

    /** All stars above the horizon as soft sprites (one quad each). */
    fun stars(catalog: SkyCatalog, frame: SkyFrame?): MeshBuilder {
        val b = MeshBuilder(catalog.stars.size * 4)
        for (s in catalog.stars) {
            if (s.vmag > 6.05f) continue
            val u = s.unit
            if (frame != null && frame.sinAlt(u) < MIN_SIN_ALT) continue
            val (t1, t2) = tangents(u)
            val h = StarLook.size(s.vmag) / 2.0
            val c = u * STAR_R
            val col = StarLook.colorClass(s.bv)
            val row = StarLook.alphaLevel(s.vmag)
            val n = (u * -1.0).f()
            val a = b.vertex((c - t1 * h - t2 * h).f(), n, SkyMaterials.atlasUv(col, row, 0f, 0f))
            val bb = b.vertex((c + t1 * h - t2 * h).f(), n, SkyMaterials.atlasUv(col, row, 1f, 0f))
            val cc = b.vertex((c + t1 * h + t2 * h).f(), n, SkyMaterials.atlasUv(col, row, 1f, 1f))
            val d = b.vertex((c - t1 * h + t2 * h).f(), n, SkyMaterials.atlasUv(col, row, 0f, 1f))
            b.quad(a, bb, cc, d)
        }
        return b
    }

    /**
     * Stick-figure segments as thin bands on the sphere, cut into pieces of at most 4° so they follow it; a piece
     * that dips under the horizon is clipped there. [only] limits it to some constellations (the lit figure).
     */
    fun lines(catalog: SkyCatalog, frame: SkyFrame?, width: Float, only: Set<Int>? = null, dashed: Boolean = false, radius: Double = LINE_R): MeshBuilder {
        val b = MeshBuilder(2048)
        for (seg in catalog.segments) {
            if (only != null && seg.constellation !in only) continue
            val ua = catalog.stars[seg.a].unit
            val ub = catalog.stars[seg.b].unit
            val angle = acos(ua.dot(ub).coerceIn(-1.0, 1.0))
            // leave a small gap at each star so the line does not run through the dot
            val gap = Math.toRadians(0.35)
            if (angle < 2 * gap) continue
            val pieces = max(1, ceil(Math.toDegrees(angle) / 4.0).toInt())
            for (i in 0 until pieces) {
                if (dashed && i % 2 == 1 && pieces > 1) continue
                val f0 = gap / angle + (1 - 2 * gap / angle) * i / pieces
                val f1 = gap / angle + (1 - 2 * gap / angle) * (i + 1) / pieces
                var p0 = slerp(ua, ub, angle, f0)
                var p1 = slerp(ua, ub, angle, f1)
                if (frame != null) {
                    val s0 = frame.sinAlt(p0)
                    val s1 = frame.sinAlt(p1)
                    if (s0 < MIN_SIN_ALT && s1 < MIN_SIN_ALT) continue
                    if (s0 < MIN_SIN_ALT || s1 < MIN_SIN_ALT) {
                        val t = ((MIN_SIN_ALT - s0) / (s1 - s0)).coerceIn(0.0, 1.0)
                        val cut = slerp(p0, p1, acos(p0.dot(p1).coerceIn(-1.0, 1.0)), t)
                        if (s0 < MIN_SIN_ALT) p0 = cut else p1 = cut
                    }
                }
                band(b, p0, p1, width, radius)
            }
        }
        return b
    }

    private fun slerp(a: Vec3d, b: Vec3d, angle: Double, t: Double): Vec3d {
        if (angle < 1e-9) return a
        val s = sin(angle)
        return (a * (sin((1 - t) * angle) / s) + b * (sin(t * angle) / s)).normalized()
    }

    private fun band(b: MeshBuilder, u0: Vec3d, u1: Vec3d, width: Float, radius: Double) {
        val p0 = u0 * radius
        val p1 = u1 * radius
        val side = u0.cross(u1).normalized() * (width / 2.0)
        val n = ((u0 + u1) * -0.5).normalized().f()
        b.quad(b.vertex((p0 - side).f(), n), b.vertex((p1 - side).f(), n), b.vertex((p1 + side).f(), n), b.vertex((p0 + side).f(), n))
    }

    /**
     * The lit part of the moon in local XY (disc of radius 0.5 facing +Z, the sun towards +X): the bright limb and
     * the terminator, an ellipse whose x is (1 - 2 f) cos(theta) for an illuminated fraction f.
     */
    fun moonLit(fraction: Double, seg: Int = 24): MeshBuilder {
        val b = MeshBuilder(seg * 2 + 2)
        val n = Vec3(0f, 0f, 1f)
        val k = 1 - 2 * fraction.coerceIn(0.0, 1.0)
        for (i in 0..seg) {
            val th = -PI / 2 + PI * i / seg
            val y = (0.5 * sin(th)).toFloat()
            b.vertex(Vec3((0.5 * cos(th)).toFloat(), y, 0.001f), n)
            b.vertex(Vec3((0.5 * k * cos(th)).toFloat(), y, 0.001f), n)
        }
        for (i in 0 until seg) b.quad(i * 2, i * 2 + 2, i * 2 + 3, i * 2 + 1)
        return b
    }
}
