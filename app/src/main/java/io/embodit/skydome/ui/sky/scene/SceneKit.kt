package io.embodit.skydome.ui.sky.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.skydome.domain.Quat4
import io.embodit.skydome.domain.StarLook
import io.embodit.skydome.domain.Vec3
import io.embodit.skydome.platform.SkyLog
import io.embodit.skydome.ui.theme.SkyColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. Every entity hangs below the stage root, whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children. */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.named(name: String): Entity = also { runCatching { it.setName(name) } }

/** Metres per dp of an attachment panel at scale 1 (Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user is
 * destroyed (Spatial Ruler), so shared ones are made global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/**
 * Every material of the sky. All Unlit (the Full style has no environment light, design container.notes), tone
 * mapping off so the design colours arrive exact (Spatial Ruler).
 */
internal class SkyMaterials(private val shared: SharedResources) {
    val domeNight: UnlitMaterial = textured(gradient(SkyColors.Zenith, SkyColors.HorizonNight), BlendingMode.OPAQUE)
    val domeDay: UnlitMaterial = textured(gradient(SkyColors.DayWash, Color(0xFF3A6A8C)), BlendingMode.OPAQUE)
    val glow: UnlitMaterial = textured(fade(), BlendingMode.TRANSPARENT).apply { setDepthWrite(false) }
    val stars: UnlitMaterial = textured(starAtlas(), BlendingMode.TRANSPARENT).apply { setDepthWrite(false) }
    val lines: UnlitMaterial = sheer(SkyColors.LineIdle, 0.35f)
    val linesLit: UnlitMaterial = solid(SkyColors.LineLit)
    val linesGhost: UnlitMaterial = sheer(SkyColors.LineIdle, 0.15f)
    val amber: UnlitMaterial = solid(SkyColors.Interaction)
    val sun: UnlitMaterial = solid(SkyColors.Sun)
    val moon: UnlitMaterial = solid(SkyColors.Moon)
    val moonDark: UnlitMaterial = solid(SkyColors.MoonDark)
    val planets: Map<String, UnlitMaterial> =
        mapOf("mercury" to solid(SkyColors.Mercury), "venus" to solid(SkyColors.Venus), "mars" to solid(SkyColors.Mars), "jupiter" to solid(SkyColors.Jupiter), "saturn" to solid(SkyColors.Saturn))
    val starBalls: List<UnlitMaterial> = SkyColors.StarClasses.map { solid(it) }
    val glassEdge: UnlitMaterial = sheer(SkyColors.GlassEdge, 0.22f)
    val white: UnlitMaterial = solid(SkyColors.StarWhite)
    val whiteSoft: UnlitMaterial = sheer(SkyColors.StarWhite, 0.55f)
    val dial: UnlitMaterial = sheer(Color(0xFF1B2238), 0.85f)
    val ground: UnlitMaterial = solid(SkyColors.Ground)
    val invisible: UnlitMaterial = sheer(SkyColors.White, 0f)

    fun solid(color: Color): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    fun sheer(color: Color, opacity: Float): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    /**
     * The engine's texture V runs from the bottom row of the bitmap up (QA round 1, q1-2: the star atlas showed its
     * opacity rows upside down, so the brightest stars were the faintest). Every bitmap here is drawn top-down with
     * v = 0 at the top, so it is flipped once before upload.
     */
    private fun textured(bitmap: Bitmap, blending: BlendingMode): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(blending).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                setBaseColorTexture(shared.keep(TextureResource.create(flipV(bitmap))))
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    companion object {
        const val ATLAS_CELL = 64

        fun flipV(src: Bitmap): Bitmap {
            val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        }

        /** Vertical gradient, v = 0 at the top (zenith) to 1 at the bottom (horizon). */
        fun gradient(top: Color, bottom: Color): Bitmap {
            val h = 64
            val bmp = Bitmap.createBitmap(4, h, Bitmap.Config.ARGB_8888)
            for (y in 0 until h) {
                val t = y / (h - 1f)
                // more of the horizon colour near the bottom: the sky is darkest overhead
                val k = t * t
                val c = Color(top.red + (bottom.red - top.red) * k, top.green + (bottom.green - top.green) * k, top.blue + (bottom.blue - top.blue) * k, 1f).toArgb()
                for (x in 0 until 4) bmp.setPixel(x, y, c)
            }
            return bmp
        }

        /** White with alpha fading out upwards: the horizon glow band (tinted by the material colour). */
        fun fade(): Bitmap {
            val h = 32
            val bmp = Bitmap.createBitmap(4, h, Bitmap.Config.ARGB_8888)
            for (y in 0 until h) {
                val t = y / (h - 1f) // 0 top .. 1 bottom
                val a = (t * t * 0.7f).coerceIn(0f, 1f)
                for (x in 0 until 4) bmp.setPixel(x, y, Color(1f, 1f, 1f, a).toArgb())
            }
            return bmp
        }

        /** Soft dots: columns = B-V colour class, rows = opacity level (StarLook). */
        fun starAtlas(): Bitmap {
            val c = ATLAS_CELL
            val bmp = Bitmap.createBitmap(c * StarLook.COLOR_CLASSES, c * StarLook.ALPHA_LEVELS, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            for (col in 0 until StarLook.COLOR_CLASSES) {
                for (row in 0 until StarLook.ALPHA_LEVELS) {
                    val base = SkyColors.StarClasses[col]
                    val a = StarLook.alphaOf(row)
                    val cx = col * c + c / 2f
                    val cy = row * c + c / 2f
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    paint.shader =
                        RadialGradient(
                            cx, cy, c * 0.46f,
                            intArrayOf(Color(1f, 1f, 1f, a).toArgb(), base.copy(alpha = a).toArgb(), base.copy(alpha = a * 0.35f).toArgb(), base.copy(alpha = 0f).toArgb()),
                            floatArrayOf(0f, 0.28f, 0.55f, 1f),
                            Shader.TileMode.CLAMP,
                        )
                    canvas.drawCircle(cx, cy, c * 0.46f, paint)
                }
            }
            return bmp
        }

        /** UV centre of an atlas cell, and its half size (inset by a pixel). */
        fun atlasUv(col: Int, row: Int, u: Float, v: Float): Vector2 {
            val cw = 1f / StarLook.COLOR_CLASSES
            val ch = 1f / StarLook.ALPHA_LEVELS
            val inset = 1f / (ATLAS_CELL * StarLook.COLOR_CLASSES)
            return Vector2(col * cw + inset + (cw - 2 * inset) * u, row * ch + inset + (ch - 2 * inset) * v)
        }
    }
}

/** Accumulates triangles and builds (or refills) a mesh. */
internal class MeshBuilder(capacity: Int = 64) {
    val positions = ArrayList<Vector3>(capacity)
    val normals = ArrayList<Vector3>(capacity)
    val uvs = ArrayList<Vector2>(capacity)
    val indices = ArrayList<Int>(capacity * 3 / 2)
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var minZ = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE

    val vertexCount: Int
        get() = positions.size

    fun vertex(p: Vec3, n: Vec3, uv: Vector2 = ZERO_UV): Int {
        positions += Vector3(p.x, p.y, p.z)
        normals += Vector3(n.x, n.y, n.z)
        uvs += uv
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
        if (p.z > maxZ) maxZ = p.z
        return positions.size - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        indices += a
        indices += b
        indices += c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        tri(a, b, c)
        tri(a, c, d)
    }

    fun bounds(): BoundingBox =
        if (positions.isEmpty()) BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.01f, 0.01f, 0.01f))
        else BoundingBox(center = Vector3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2), halfExtent = Vector3(max(0.001f, (maxX - minX) / 2), max(0.001f, (maxY - minY) / 2), max(0.001f, (maxZ - minZ) / 2)))

    /** A degenerate triangle keeps an emptied mesh valid. */
    private fun ensureNotEmpty() {
        if (positions.isNotEmpty()) return
        val n = Vec3(0f, 0f, 1f)
        tri(vertex(Vec3.ZERO, n), vertex(Vec3(0f, 0.0001f, 0f), n), vertex(Vec3(0.0001f, 0f, 0f), n))
    }

    fun model(): MeshModel {
        ensureNotEmpty()
        return MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs)
    }

    fun build(name: String): MeshResource = MeshResource.createWithMeshModel(model = model(), bounds = bounds(), name = name)

    /** Refills [mesh]; returns false when the engine refused (the caller keeps the old one). */
    fun replace(mesh: MeshResource): Boolean =
        try {
            val m = model()
            mesh.replaceWithMeshModel(m, bounds())
        } catch (t: Throwable) {
            SkyLog.e("SKY mesh replace failed: $t", t)
            false
        }

    companion object {
        val ZERO_UV = Vector2(0.5f, 0.5f)
    }
}

/** Programmatic meshes, built once per scene. */
internal object Shapes {
    /** Inside of a sphere cap from altitude [fromAltDeg] to the zenith, local +Y up; v = 0 at the zenith. */
    fun dome(radius: Float, fromAltDeg: Float, toAltDeg: Float = 90f, seg: Int = 64, rings: Int = 24, name: String): MeshResource {
        val b = MeshBuilder((seg + 1) * (rings + 1))
        for (r in 0..rings) {
            val alt = Math.toRadians((fromAltDeg + (toAltDeg - fromAltDeg) * r / rings).toDouble())
            val v = 1f - r.toFloat() / rings
            for (s in 0..seg) {
                val az = 2 * PI * s / seg
                val p = Vec3((cos(alt) * sin(az)).toFloat(), sin(alt).toFloat(), (-cos(alt) * cos(az)).toFloat())
                b.vertex(p * radius, -p, Vector2(s.toFloat() / seg, v))
            }
        }
        val w = seg + 1
        for (r in 0 until rings) for (s in 0 until seg) b.quad(r * w + s, r * w + s + 1, (r + 1) * w + s + 1, (r + 1) * w + s)
        return b.build(name)
    }

    /** A flat disc of radius 0.5 in local XY facing +Z. */
    fun disc(name: String, seg: Int = 40): MeshResource {
        val b = MeshBuilder(seg + 2)
        val n = Vec3(0f, 0f, 1f)
        val c = b.vertex(Vec3.ZERO, n)
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            b.vertex(Vec3((0.5 * cos(a)).toFloat(), (0.5 * sin(a)).toFloat(), 0f), n)
        }
        for (i in 1..seg) b.tri(c, i, i + 1)
        return b.build(name)
    }

    /** A flat ring (annulus) between radii [inner] and 0.5 in local XY facing +Z. */
    fun ring(name: String, inner: Float, seg: Int = 48): MeshResource {
        val b = MeshBuilder(seg * 2 + 2)
        val n = Vec3(0f, 0f, 1f)
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            b.vertex(Vec3((inner * cos(a)).toFloat(), (inner * sin(a)).toFloat(), 0f), n)
            b.vertex(Vec3((0.5 * cos(a)).toFloat(), (0.5 * sin(a)).toFloat(), 0f), n)
        }
        for (i in 0 until seg) b.quad(i * 2, i * 2 + 1, i * 2 + 3, i * 2 + 2)
        return b.build(name)
    }

    /** A flat arrow in local XY pointing +X, 1 m long overall (scaled), facing +Z. */
    fun arrow(name: String, bent: Boolean): MeshResource {
        val b = MeshBuilder(64)
        val n = Vec3(0f, 0f, 1f)
        val w = 0.09f
        if (!bent) {
            b.quad(b.vertex(Vec3(-0.5f, -w, 0f), n), b.vertex(Vec3(0.1f, -w, 0f), n), b.vertex(Vec3(0.1f, w, 0f), n), b.vertex(Vec3(-0.5f, w, 0f), n))
        } else {
            // a quarter arc from below, curving into +X
            val seg = 12
            val r0 = 0.45f - w
            val r1 = 0.45f + w
            val cx = 0.1f
            val cy = -0.45f
            val base = b.vertexCount
            for (i in 0..seg) {
                val a = PI - (PI / 2) * i / seg
                b.vertex(Vec3(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), 0f), n)
                b.vertex(Vec3(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), 0f), n)
            }
            for (i in 0 until seg) b.quad(base + i * 2, base + i * 2 + 1, base + i * 2 + 3, base + i * 2 + 2)
        }
        b.tri(b.vertex(Vec3(0.1f, -0.24f, 0f), n), b.vertex(Vec3(0.5f, 0f, 0f), n), b.vertex(Vec3(0.1f, 0.24f, 0f), n))
        return b.build(name)
    }

    /** A unit cube centred on the origin. */
    fun cube(name: String): MeshResource = MeshResource.createBox(Vector3(1f, 1f, 1f), 0f)

    /** A sphere of diameter 1 (scaled per star). */
    fun ball(name: String): MeshResource = MeshResource.createSphere(0.5f)

    /** A cylinder of diameter 1 and height 1 about local Y (the dial disc; built here so its size is exact). */
    fun cylinder(name: String, seg: Int = 48): MeshResource {
        val b = MeshBuilder(seg * 4 + 2)
        val up = Vec3(0f, 1f, 0f)
        val down = Vec3(0f, -1f, 0f)
        val top = b.vertex(Vec3(0f, 0.5f, 0f), up)
        val bottom = b.vertex(Vec3(0f, -0.5f, 0f), down)
        for (i in 0 until seg) {
            val a0 = 2 * PI * i / seg
            val a1 = 2 * PI * (i + 1) / seg
            val p0 = Vec3((0.5 * cos(a0)).toFloat(), 0f, (0.5 * sin(a0)).toFloat())
            val p1 = Vec3((0.5 * cos(a1)).toFloat(), 0f, (0.5 * sin(a1)).toFloat())
            val h = Vec3(0f, 0.5f, 0f)
            b.tri(top, b.vertex(p1 + h, up), b.vertex(p0 + h, up))
            b.tri(bottom, b.vertex(p0 - h, down), b.vertex(p1 - h, down))
            val n = p0.normalized()
            b.quad(b.vertex(p0 - h, n), b.vertex(p1 - h, n), b.vertex(p1 + h, n), b.vertex(p0 + h, n))
        }
        return b.build(name)
    }
}

/** Places a unit-cube entity as a thin bar between two points. */
internal fun Entity.bar(from: Vec3, to: Vec3, thickness: Float) {
    val d = to - from
    val len = d.length
    if (len < 1e-5f) {
        setLocal(scale = Vec3(1e-4f, 1e-4f, 1e-4f))
        return
    }
    val dir = d / len
    setLocal(position = (from + to) * 0.5f, rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), dir), scale = Vec3(thickness, thickness, len))
}

internal fun model(mesh: MeshResource, material: Material, name: String, parent: Entity): ModelEntity =
    ModelEntity(mesh, material).also {
        runCatching { it.setName(name) }
        parent.addChild(it)
    }
