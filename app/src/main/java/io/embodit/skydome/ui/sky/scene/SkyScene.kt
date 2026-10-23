package io.embodit.skydome.ui.sky.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.skydome.domain.Astro
import io.embodit.skydome.domain.Body
import io.embodit.skydome.domain.BodyState
import io.embodit.skydome.domain.Dial
import io.embodit.skydome.domain.HeadPose
import io.embodit.skydome.domain.ModelStar
import io.embodit.skydome.domain.PulledLayout
import io.embodit.skydome.domain.Quat4
import io.embodit.skydome.domain.SkyCatalog
import io.embodit.skydome.domain.SkyFacts
import io.embodit.skydome.domain.SkyFrame
import io.embodit.skydome.domain.StarLook
import io.embodit.skydome.domain.StickTurn
import io.embodit.skydome.domain.Vec3
import io.embodit.skydome.domain.Vec3d
import io.embodit.skydome.domain.Yaw
import io.embodit.skydome.domain.arcDistance
import io.embodit.skydome.platform.SkyLog
import io.embodit.skydome.platform.tracking.ControllerStick
import io.embodit.skydome.platform.tracking.HeadTracker
import io.embodit.skydome.ui.sky.Layer
import io.embodit.skydome.ui.sky.Pinch
import io.embodit.skydome.ui.sky.Popup
import io.embodit.skydome.ui.sky.SkyController
import io.embodit.skydome.ui.sky.SkyEvent
import io.embodit.skydome.ui.sky.SkyUiState
import io.embodit.skydome.ui.sky.Target
import io.embodit.skydome.ui.sky.components.PanelSizes
import io.embodit.skydome.ui.theme.SkyColors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The scenes alive now: one per open Stage (two for a moment while switching Mixed <-> Full). Main thread only. */
internal object ActiveScenes {
    val all = ArrayList<SkyScene>(2)

    /** The scene of the Stage that is open now (debug hooks talk to this one). */
    fun current(): SkyScene? = all.firstOrNull { it.label == SkyController.currentStageId } ?: all.lastOrNull()
}

/**
 * The one per-frame ECS system of the app (`registerSystem` in SkyScreen). It hands the frame to every live scene;
 * each one works only when the frame belongs to its own ECS scene.
 */
class SkyFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        for (i in ActiveScenes.all.indices.reversed()) ActiveScenes.all.getOrNull(i)?.onFrame(context)
    }
}

/** Panel ids of the pool. */
object PanelIds {
    const val BAR = "handBar"
    const val CAL = "calibrate"
    const val TIME = "timePlace"
    const val TONIGHT = "tonight"
    const val SETTINGS = "settings"
    const val CARD = "card"
    const val BODY = "bodyCard"
    const val HOVER = "hoverTag"
    const val READOUT = "dialReadout"
    const val TOAST = "toast"
    const val EYE = "eyeTag"
    const val BODY_TAGS = 6
    const val COMPASS_TAGS = 4
    const val TICKS = 3

    /** Order of the body tags: moon, then the planets. */
    val TAG_BODIES = listOf(Body.MOON, Body.MERCURY, Body.VENUS, Body.MARS, Body.JUPITER, Body.SATURN)

    fun bodyTag(i: Int) = "bodyTag$i"

    fun compass(i: Int) = "compass$i"

    fun tick(i: Int) = "tick$i"

    val pool: List<String> =
        listOf(BAR, CAL, TIME, TONIGHT, SETTINGS, CARD, BODY, HOVER, READOUT, TOAST, EYE) +
            (0 until BODY_TAGS).map { bodyTag(it) } + (0 until COMPASS_TAGS).map { compass(it) } + (0 until TICKS).map { tick(it) }
}

/**
 * The 3D side of the app (design `entities3d`, `spatialLayout`): the dome, the celestial sphere and what hangs on
 * it, the hand group (time dial), the pulled-down constellation, the guide arrow and the panel pool. Per-frame
 * work happens in [onFrame] only (one ECS system): the sky turns there, hover and the guide are worked out
 * there, panels are placed there; the only Compose state written here is which panel is in view, on change.
 * The 24 h dial and model turning send events at most 10 times a second. Main thread only.
 */
class SkyScene(private val sink: (SkyEvent) -> Unit, val label: String = "") {

    /** Panel id -> in view. A pooled panel composes its content only while this is true (Spatial Ruler finding 3). */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    /** Live dial readout ("+3 小时 · 00:30"), written at most 10 times a second while dragging. */
    val dialReadout = mutableStateMapOf<String, Long>()

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private val head = HeadTracker()
    private val stick = ControllerStick()

    // thumbstick turn of the pulled-down model (taste profile D1)
    private var stickRate = 0f
    private var stickActive = false
    private var lastModelStepMs = 0L
    private var debugStick = 0f
    private var debugStickUntilMs = 0L
    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var mats: SkyMaterials? = null
    private var buildJob: Job? = null
    private var built = false
    private var builtAtMs = -1L
    private var attached = false
    private var paused = false

    // ---- the sky
    private var domeRoot: Entity? = null
    private var dome: ModelEntity? = null
    private var glow: ModelEntity? = null
    private var ground: ModelEntity? = null
    private var celestial: Entity? = null
    private var starMesh: MeshResource? = null
    private var lineMesh: MeshResource? = null
    private var litMesh: MeshResource? = null
    private var ghostMesh: MeshResource? = null
    private var lineEntity: ModelEntity? = null
    private var litEntity: ModelEntity? = null
    private var ghostEntity: ModelEntity? = null
    private var domeCollider: Entity? = null
    private val bodies = LinkedHashMap<Body, BodyView>()
    private var ring: ModelEntity? = null
    private var discMesh: MeshResource? = null
    private var ringMesh: MeshResource? = null
    private var ballMesh: MeshResource? = null
    private var cubeMesh: MeshResource? = null

    // ---- hand group, calibration, guide
    private var dialRoot: Entity? = null
    private var dialHandle: Entity? = null
    private var dialNotch: Entity? = null
    private var dialIcon: ModelEntity? = null
    private var floorRing: Entity? = null
    private var floorArrow: Entity? = null
    private var arrowStraight: ModelEntity? = null
    private var arrowUp: ModelEntity? = null

    // ---- pulled-down model
    private var model: PulledModel? = null
    private var leaving: PulledModel? = null

    private var pose = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
    private var hasHead = false
    private var ui = SkyUiState()
    private var frames = 0L
    private var lastLogMs = 0L

    // sky refresh bookkeeping
    private var frame: SkyFrame? = null
    private var shownTimeMs = Long.MIN_VALUE
    private var clipTimeMs = Long.MIN_VALUE
    private var clipWallMs = 0L
    private var clipKey = ""
    private var bodiesTimeMs = Long.MIN_VALUE
    private var bodyStates: List<BodyState> = emptyList()
    private var litKey = ""
    private var sunAlt = -90.0
    private var lastLook = ""

    // hover
    private var hoverCandidate: Target? = null
    private var candidateSinceMs = 0L
    private var lostSinceMs = -1L

    // hand group follow (design spatialLayout.handGroup)
    private var groupYaw = 0f
    private var groupPitch = 0f
    private var groupSet = false
    private var outSinceMs = -1L
    private var slideFrom = 0f
    private var slideTo = 0f
    private var slideStartMs = -1L
    private var reclined = false
    private var pitchHighSinceMs = -1L
    private var pitchLowSinceMs = -1L
    private var calYaw = 0f
    private val yawHistory = ArrayDeque<Pair<Long, Float>>()

    // body card gaze-away timer
    private var bodyAwaySinceMs = -1L
    private var bodyCardAt: Vec3? = null
    private var bodyCardFrom: Vec3? = null
    private var bodyCardStartMs = 0L
    private var bodyCardFor: Body? = null

    // guide
    private var guideArrivedSent = false

    // dial drag
    private class DialDrag(val startTimeMs: Long, val handleLocal: Vec3) {
        var prevMinute = 0.0
        var accumulated = 0.0
        var totalPx = Vec3.ZERO
        var liveMs = startTimeMs
        var lastSentMs = 0L
    }

    private var dialDrag: DialDrag? = null

    // model turning
    private class TurnDrag(val startYaw: Float) {
        var totalPx = Vec3.ZERO
    }

    private var turnDrag: TurnDrag? = null
    private var lastDragEndMs = -1L

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (Spatial Ruler): move the root once
        // so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        SkyLog.i("STAGE root offset=${SkyLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.BAR, PanelSizes.BAR_W, PanelSizes.BAR_H, BAR_K)
        rig.world(PanelIds.CAL, PanelSizes.CAL_W, PanelSizes.CAL_H, BAR_K)
        rig.world(PanelIds.TIME, PanelSizes.TIME_W, PanelSizes.TIME_H, POPUP_K)
        rig.world(PanelIds.TONIGHT, PanelSizes.TONIGHT_W, PanelSizes.TONIGHT_H, POPUP_K)
        rig.world(PanelIds.SETTINGS, PanelSizes.SETTINGS_W, PanelSizes.SETTINGS_H, POPUP_K)
        rig.world(PanelIds.CARD, PanelSizes.CARD_W, PanelSizes.CARD_H, CARD_K)
        rig.world(PanelIds.BODY, PanelSizes.BODY_W, PanelSizes.BODY_H, CARD_K)
        rig.world(PanelIds.HOVER, PanelSizes.TAG_W, PanelSizes.TAG_H, SKY_TAG_K).maxPitch = SKY_PITCH
        rig.world(PanelIds.READOUT, PanelSizes.READOUT_W, PanelSizes.READOUT_H, READOUT_K)
        rig.world(PanelIds.TOAST, PanelSizes.TOAST_W, PanelSizes.TOAST_H, TOAST_K)
        rig.world(PanelIds.EYE, PanelSizes.READOUT_W, PanelSizes.READOUT_H, MODEL_TAG_K)
        for (i in 0 until PanelIds.BODY_TAGS) rig.world(PanelIds.bodyTag(i), PanelSizes.SMALL_TAG_W, PanelSizes.TAG_H, SKY_TAG_K).maxPitch = SKY_PITCH
        for (i in 0 until PanelIds.COMPASS_TAGS) rig.world(PanelIds.compass(i), PanelSizes.SMALL_TAG_W, PanelSizes.TAG_H, SKY_TAG_K).maxPitch = SKY_PITCH
        for (i in 0 until PanelIds.TICKS) rig.world(PanelIds.tick(i), PanelSizes.READOUT_W, PanelSizes.READOUT_H, MODEL_TAG_K)
        head.start()
        stick.start()
        if (this !in ActiveScenes.all) ActiveScenes.all += this
        attached = true
        bindPanels(attachments)
        buildJob =
            scope?.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) SkyLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        SkyLog.i("SCENE attached stage=$label")
    }

    /** Materials and meshes; the build yields between steps (a mesh costs about a frame; Spatial Ruler ANR). */
    private suspend fun build(root: Entity, t0: Long) {
        while (SkyController.catalog == null) delay(50)
        val catalog = SkyController.catalog ?: return
        delay(150)
        val shared = SharedResources()
        this.shared = shared
        val m = SkyMaterials(shared)
        mats = m
        yield()
        discMesh = shared.keep(Shapes.disc("disc"))
        ringMesh = shared.keep(Shapes.ring("ring", 0.42f))
        ballMesh = shared.keep(Shapes.ball("ball"))
        cubeMesh = shared.keep(Shapes.cube("cube"))
        yield()
        val dr = Entity().named("domeRoot")
        root.addChild(dr)
        domeRoot = dr
        dome = model(shared.keep(Shapes.dome(DOME_R, -2f, name = "dome")), m.domeNight, "dome", dr)
        yield()
        glow = model(shared.keep(Shapes.dome(DOME_R - 0.03f, -2f, 4f, seg = 64, rings = 2, name = "glow")), m.glow, "glow", dr)
        // "全黑": the whole lower half is dark ground right up to the glow band (QA q1-1: a flat disc 1.55 m down
        // left a pure black band between -2° and its edge at -15°)
        ground = model(shared.keep(Shapes.dome(DOME_R - 0.02f, -90f, -1.5f, seg = 64, rings = 12, name = "ground")), m.ground, "ground", dr).also { g -> g.enabled = false }
        // tap target for the inside of the dome (design needsCheck N8): a static mesh shape of a coarse cap
        domeCollider = Entity().named("domeCollider").also { c ->
            val coarse = shared.keep(Shapes.dome(DOME_R - 0.1f, -2f, seg = 24, rings = 8, name = "domeCoarse"))
            runCatching {
                c.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createStaticMesh(coarse)), physicsMaterial = PhysicsMaterialResource()))
                c.components.set(InteractableComponent())
            }.onFailure { SkyLog.w("SKY dome collider failed: $it") }
            dr.addChild(c)
        }
        yield()
        val cel = Entity().named("celestial")
        dr.addChild(cel)
        celestial = cel
        val sm = shared.keep(SkyGeometry.stars(catalog, null).build("stars"))
        starMesh = sm
        model(sm, m.stars, "stars", cel)
        yield()
        val lm = shared.keep(SkyGeometry.lines(catalog, null, SkyGeometry.LINE_W).build("lines"))
        lineMesh = lm
        lineEntity = model(lm, m.lines, "lines", cel)
        litMesh = shared.keep(MeshBuilder().build("litLines"))
        litEntity = model(litMesh!!, m.linesLit, "litLines", cel).also { it.enabled = false }
        ghostMesh = shared.keep(MeshBuilder().build("ghostLines"))
        ghostEntity = model(ghostMesh!!, m.linesGhost, "ghostLines", cel).also { it.enabled = false }
        yield()
        for (b in Body.entries) bodies[b] = BodyView(b, cel, discMesh!!, m, shared)
        ring = model(ringMesh!!, m.amber, "ring", cel).also { it.enabled = false }
        yield()
        buildHandGroup(root, m)
        buildGuide(root, m, shared)
        built = true
        builtAtMs = SystemClock.elapsedRealtime()
        dr.setOpacity(0f)
        SkyLog.i("SCENE built stage=$label ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui)
        sink(SkyEvent.SceneReady)
    }

    private fun buildHandGroup(root: Entity, m: SkyMaterials) {
        val d = Entity().named("dial")
        root.addChild(d)
        dialRoot = d
        model(Shapes.cylinder("dialDisc").also { shared?.keep(it) }, m.dial, "dialDisc", d).setLocal(scale = Vec3(DIAL_D, DIAL_T, DIAL_D))
        for (h in 0 until 24) {
            val a = Dial.angleOf(h * 60.0)
            val major = h % 6 == 0
            val r0 = if (major) 0.074f else 0.083f
            val p0 = Vec3((-sin(a) * r0).toFloat(), DIAL_T / 2 + 0.001f, (-cos(a) * r0).toFloat())
            val p1 = Vec3((-sin(a) * 0.1f).toFloat(), DIAL_T / 2 + 0.001f, (-cos(a) * 0.1f).toFloat())
            model(cubeMesh!!, if (major) m.white else m.whiteSoft, "tick$h", d).bar(p0, p1, if (major) 0.004f else 0.0022f)
        }
        dialNotch = model(cubeMesh!!, m.amber, "nowNotch", d)
        dialIcon = model(discMesh!!, m.moon, "dialIcon", d).also { it.setLocal(position = Vec3(0f, DIAL_T / 2 + 0.002f, 0f), rotation = Quat4.axisAngle(Vec3.RIGHT, -(PI / 2).toFloat()), scale = Vec3(0.04f, 0.04f, 1f)) }
        val handle = model(ballMesh!!, m.amber, "dialHandle", d)
        handle.setLocal(scale = Vec3(0.03f, 0.03f, 0.03f))
        runCatching {
            handle.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createSphere(0.9f)), physicsMaterial = PhysicsMaterialResource()))
            handle.components.set(InteractableComponent())
        }
        dialHandle = handle
        d.enabled = false

        val fr = model(ringMesh!!, m.whiteSoft, "floorRing", root)
        fr.setLocal(scale = Vec3(1f, 1f, 1f))
        floorRing = fr
        floorArrow = model(Shapes.arrow("floorArrow", bent = false).also { shared?.keep(it) }, m.amber, "floorArrow", root)
    }

    private fun buildGuide(root: Entity, m: SkyMaterials, shared: SharedResources) {
        arrowStraight = model(shared.keep(Shapes.arrow("arrow", bent = false)), m.amber, "guideArrow", root).also { it.enabled = false }
        arrowUp = model(shared.keep(Shapes.arrow("arrowUp", bent = true)), m.amber, "guideArrowUp", root).also { it.enabled = false }
    }

    /** The Stage went to the background: stop head tracking (design `container.lifecycle`). */
    fun pause() {
        if (paused || !attached) return
        paused = true
        head.stop()
        stick.stop()
        dialDrag = null
        turnDrag = null
        SkyLog.i("SCENE paused stage=$label")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        stick.start()
        // back from the background: "now" is refreshed by the controller's clock; force a sky refresh
        clipTimeMs = Long.MIN_VALUE
        bodiesTimeMs = Long.MIN_VALUE
        SkyLog.i("SCENE resumed stage=$label")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        ActiveScenes.all.remove(this)
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        head.stop()
        stick.stop()
        panelShown.clear()
        model?.destroy()
        leaving?.destroy()
        model = null
        leaving = null
        bodies.clear()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        mats = null
        stageRoot = null
        contentProbe = null
        domeRoot = null
        celestial = null
        rig = null
        content = null
        built = false
        hasHead = false
        dialDrag = null
        turnDrag = null
        SkyLog.i("SCENE detached stage=$label")
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: SkyUiState) {
        val before = ui
        ui = state
        if (!built) return
        val cat = SkyController.catalog ?: return
        if (state.pulled != before.pulled || model == null && state.pulled != null) onPulledChanged(before.pulled, state.pulled, cat)
        model?.setDepth(state.depth)
        if (state.bodyCard != before.bodyCard) {
            bodyAwaySinceMs = -1L
            bodyCardFor = state.bodyCard
            bodyCardStartMs = SystemClock.elapsedRealtime()
            bodyCardAt = null
        }
        if (state.guide?.startedAtMs != before.guide?.startedAtMs) guideArrivedSent = false
        lineEntity?.enabled = state.layer(Layer.LINES)
        ground?.enabled = state.fullDark
        litKey = "" // the lit figure follows hover / pulled / layers
    }

    private fun onPulledChanged(old: Int?, new: Int?, cat: SkyCatalog) {
        val root = stageRoot ?: return
        val m = mats ?: return
        model?.let { prev ->
            leaving?.destroy()
            leaving = prev
            prev.leave(skyPointFor(prev.constellation), aboveHorizon(prev.constellation))
        }
        model = null
        if (new == null) return
        val c = cat.constellations[new]
        val layout = PulledLayout.layout(cat, c)
        val span = PulledLayout.angularSpan(cat, c)
        val pm = PulledModel(new, layout, cat, root, m, ballMesh!!, cubeMesh!!, discMesh!!)
        val yaw = pose.yaw(groupYaw)
        val at = pose.position + Yaw.dir(yaw) * PULL_M + Vec3(0f, -0.10f, 0f)
        val skyScale = ((SkyGeometry.STAR_R * span) / LONGEST_M).toFloat().coerceIn(1f, 20f)
        pm.arrive(skyPointFor(new), at, yaw, skyScale)
        model = pm
        SkyLog.i("PULL ${c.abbr} stars=${layout.size} span=${String.format(Locale.US, "%.1f", Math.toDegrees(span))} at=${SkyLog.pos(at)} old=$old")
    }

    private fun skyPointFor(index: Int): Vec3 {
        val cat = SkyController.catalog ?: return pose.position
        val f = frame ?: return pose.position
        val center = ui.domeCenter ?: pose.position
        return center + f.toStage(cat.constellations[index].centre.unit()) * SkyGeometry.STAR_R.toFloat()
    }

    private fun aboveHorizon(index: Int): Boolean {
        val cat = SkyController.catalog ?: return true
        val f = frame ?: return true
        return f.sinAlt(cat.constellations[index].centre.unit()) > 0
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        frames++
        head.pose()?.let {
            pose = it
            hasHead = it.position.length > 1e-3f
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        if (!attached || paused) return
        val cat = SkyController.catalog
        val state = ui
        if (built && cat != null) {
            val wall = SkyController.clock()
            val t = dialDrag?.liveMs ?: state.displayTime(wall)
            stepSky(cat, state, t, now)
            stepHover(cat, state, now)
            stepGuide(cat, state, now)
            stepBodyCard(state, now)
            stepModel(now)
            stepHandGroup(state, now)
            stepCalibration(state, now)
        }
        placePanels(state, now)
        rig.faceViewer(pose.position)
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            SkyLog.i("FRAME n=$frames head=${SkyLog.pos(pose.position)} fwd=${SkyLog.pos(pose.forward)} state=${SkyController.designState()} clip=${String.format(Locale.US, "%tT", clipTimeMs)} controller(${stick.status()})")
        }
    }

    private fun stepSky(cat: SkyCatalog, state: SkyUiState, t: Long, now: Long) {
        val city = SkyController.city(state) ?: return
        val dr = domeRoot ?: return
        // S00: the dome fades in over 0.6 s once built
        if (builtAtMs >= 0) {
            val q = ((now - builtAtMs) / FADE_IN_MS).coerceIn(0f, 1f)
            dr.setOpacity(q)
            if (q >= 1f) builtAtMs = -1L
        }
        val center = state.domeCenter ?: if (hasHead) pose.position else Vec3(0f, 1.6f, 0f)
        dr.setLocal(position = center)
        val f = SkyFrame(city.lat, Astro.lst(t, city.lon), state.northYaw)
        val key = "${state.cityId}|${state.northYaw}"
        if (t != shownTimeMs || frame == null || clipKey.substringBefore('#') != key) {
            frame = f
            shownTimeMs = t
            celestial?.setLocal(rotation = f.rotation())
        }
        // which stars and lines are above the horizon: every minute of sky time, at most 10 times a second
        val moving = state.fast || dialDrag != null
        val due = abs(t - clipTimeMs) >= CLIP_SKY_MS && (!moving || now - clipWallMs >= CLIP_MOVING_MS)
        val litWanted = litKeyFor(state)
        if (due || clipKey != "$key#${state.pulled}") {
            clipTimeMs = t
            clipWallMs = now
            clipKey = "$key#${state.pulled}"
            starMesh?.let { SkyGeometry.stars(cat, f).replace(it) }
            lineMesh?.let { mesh ->
                val all = cat.constellations.indices.toSet() - setOfNotNull(state.pulled)
                SkyGeometry.lines(cat, f, SkyGeometry.LINE_W, only = all).replace(mesh)
            }
            litKey = ""
        }
        if (litWanted != litKey) {
            litKey = litWanted
            val lit = litSet(state)
            litEntity?.enabled = lit.isNotEmpty()
            if (lit.isNotEmpty()) litMesh?.let { SkyGeometry.lines(cat, f, SkyGeometry.LIT_W, only = lit, radius = SkyGeometry.LIT_R).replace(it) }
            val ghost = state.pulled
            ghostEntity?.enabled = ghost != null
            if (ghost != null) ghostMesh?.let { SkyGeometry.lines(cat, f, SkyGeometry.LINE_W, only = setOf(ghost), dashed = true).replace(it) }
        }
        // bodies move slowly: every minute of sky time (every frame of a fast sky is not needed)
        if (abs(t - bodiesTimeMs) >= CLIP_SKY_MS || bodyStates.isEmpty()) {
            bodiesTimeMs = t
            bodyStates = Astro.bodies(t, city.lat, city.lon)
            sunAlt = Astro.horizontalAt(bodyStates[0].eq, Astro.lst(t, city.lon), city.lat).altDeg
            for (bs in bodyStates) bodies[bs.body]?.update(bs, bodyStates[0], f, state.layer(Layer.BODIES))
        }
        for (bv in bodies.values) bv.setVisible(f, state.layer(Layer.BODIES))
        // looks: day, calibration dimming
        val day = sunAlt > SkyFacts.DAY_SUN_ALT
        val dim = state.calibrating
        val look = "$day|$dim|${(sunAlt / 2).toInt()}"
        if (look != lastLook) {
            lastLook = look
            val m = mats ?: return
            dome?.setMaterial(if (day) m.domeDay else m.domeNight)
            val k = if (dim) 0.5f else 1f
            m.domeDay.setBaseColor(Color4(k, k, k, 1f))
            m.domeNight.setBaseColor(Color4(k, k, k, 1f))
            m.stars.setOpacity(if (day) 0.4f else if (dim) 0.6f else 1f)
            m.glow.setBaseColor(glowColor(sunAlt))
            SkyLog.i("SKY look day=$day dim=$dim sun_alt=${String.format(Locale.US, "%.1f", sunAlt)}")
        }
    }

    private fun glowColor(sunAlt: Double): Color4 {
        val night = SkyColors.HorizonNight
        val dusk = SkyColors.HorizonDusk
        val dayC = SkyColors.StarBlue
        val c =
            when {
                sunAlt > 2 -> dayC
                sunAlt > -12 -> {
                    val t = ((sunAlt + 12) / 14).toFloat().coerceIn(0f, 1f)
                    val k = 1f - abs(t - 0.6f) / 0.6f
                    androidx.compose.ui.graphics.lerp(night.copy(red = 0.25f, green = 0.3f, blue = 0.5f), dusk, k.coerceIn(0f, 1f))
                }
                else -> night.copy(red = 0.22f, green = 0.27f, blue = 0.45f)
            }
        return Color4(c.red, c.green, c.blue, 1f)
    }

    private fun litSet(state: SkyUiState): Set<Int> {
        val out = HashSet<Int>()
        (state.hover as? Target.Constellation)?.let { out += it.index }
        val g = state.guide
        if (g?.arrivedAtMs != null) (g.target as? Target.Constellation)?.let { out += it.index }
        state.pulled?.let { out -= it }
        return out
    }

    private fun litKeyFor(state: SkyUiState): String = "${litSet(state)}|${state.pulled}"

    // ---------------------------------------------------------------- hover (design T2)

    private fun hoverAllowed(state: SkyUiState): Boolean =
        !state.calibrating && state.popup == null && !state.dialDragging && dialDrag == null && !state.fast && state.bodyCard == null && !gazeOnModel()

    private fun gazeOnModel(): Boolean {
        val m = model ?: return false
        val to = m.position - pose.position
        val d = to.length
        if (d < 1e-3f) return false
        return angleDeg(pose.forward, to / d) < 22f
    }

    private fun stepHover(cat: SkyCatalog, state: SkyUiState, now: Long) {
        val f = frame ?: return
        if (!hoverAllowed(state)) {
            hoverCandidate = null
            if (state.hover != null) sink(SkyEvent.HoverChanged(null))
            return
        }
        val d = debugGaze ?: pose.forward
        val eq = f.toEquatorial(d)
        val current = state.hover
        val found = targetAt(cat, f, eq, if (current is Target.Constellation) EXIT_RAD else ENTER_RAD, keep = current, bodiesOn = state.layer(Layer.BODIES))
        if (found == current) {
            hoverCandidate = null
            lostSinceMs = -1L
            return
        }
        if (found == null) {
            if (current == null) return
            if (lostSinceMs < 0) lostSinceMs = now
            if (now - lostSinceMs >= DWELL_MS) {
                lostSinceMs = -1L
                sink(SkyEvent.HoverChanged(null))
            }
            return
        }
        lostSinceMs = -1L
        if (found != hoverCandidate) {
            hoverCandidate = found
            candidateSinceMs = now
        } else if (now - candidateSinceMs >= DWELL_MS) {
            hoverCandidate = null
            SkyLog.i("HOVER ${describe(cat, found)}")
            sink(SkyEvent.HoverChanged(found))
        }
    }

    /**
     * What is at an equatorial direction: a body within 3° (first), else the nearest constellation figure within
     * [limit] (radians); [keep] wins a tie so the lit one does not flicker. Only above the horizon.
     */
    private fun targetAt(cat: SkyCatalog, f: SkyFrame, eq: Vec3d, limit: Double, keep: Target?, bodiesOn: Boolean): Target? {
        if (bodiesOn) {
            var best: Body? = null
            var bestA = BODY_RAD
            for (bs in bodyStates) {
                val u = bs.eq.unit()
                if (f.sinAlt(u) < 0) continue
                val a = acos(u.dot(eq).coerceIn(-1.0, 1.0))
                if (a < bestA) {
                    bestA = a
                    best = bs.body
                }
            }
            if (best != null) return Target.OfBody(best)
        }
        var bestC = -1
        var bestD = max(limit, ENTER_RAD)
        val keepIdx = (keep as? Target.Constellation)?.index
        for (seg in cat.segments) {
            if (seg.constellation == ui.pulled) continue
            val a = cat.stars[seg.a].unit
            val b = cat.stars[seg.b].unit
            if (f.sinAlt(a) < 0 && f.sinAlt(b) < 0) continue
            val dist = arcDistance(eq, a, b)
            val lim = if (seg.constellation == keepIdx) limit else ENTER_RAD
            if (dist <= lim && (dist < bestD || bestC == -1)) {
                bestD = dist
                bestC = seg.constellation
            }
        }
        return if (bestC >= 0) Target.Constellation(bestC) else null
    }

    private fun describe(cat: SkyCatalog, t: Target): String =
        when (t) {
            is Target.Constellation -> "constellation=${cat.constellations[t.index].abbr}"
            is Target.OfBody -> "body=${t.body.id}"
        }

    // ---------------------------------------------------------------- guide (design S10)

    private fun targetDirection(cat: SkyCatalog, t: Target): Vec3? {
        val f = frame ?: return null
        val u =
            when (t) {
                is Target.Constellation -> cat.constellations[t.index].centre.unit()
                is Target.OfBody -> bodyStates.firstOrNull { it.body == t.body }?.eq?.unit() ?: return null
            }
        return f.toStage(u)
    }

    private fun stepGuide(cat: SkyCatalog, state: SkyUiState, now: Long) {
        val g = state.guide
        val straight = arrowStraight
        val up = arrowUp
        if (g == null) {
            straight?.enabled = false
            up?.enabled = false
            if (ui.hover == null && model == null) ring?.enabled = false
            return
        }
        val dir = targetDirection(cat, g.target) ?: return
        val away = angleDeg(pose.forward, dir)
        if (g.arrivedAtMs == null && away < ARRIVE_DEG && !guideArrivedSent) {
            guideArrivedSent = true
            SkyLog.i("GUIDE arrived ${describe(cat, g.target)}")
            sink(SkyEvent.GuideArrived)
            scope?.launch {
                delay(2000)
                sink(SkyEvent.GuideEnded)
            }
        }
        if (g.arrivedAtMs != null || guideArrivedSent) {
            straight?.enabled = false
            up?.enabled = false
            // pulse ring around the target for 2 s
            val r = ring ?: return
            val f = frame ?: return
            val u = dir.normalized()
            val k = 1f + 0.15f * sin((now % 600) / 600f * 2f * PI.toFloat())
            r.setLocal(position = celestialLocal(f, u * (SkyGeometry.BODY_R.toFloat())), rotation = facingCenter(f, u), scale = Vec3(0.6f * k, 0.6f * k, 1f))
            r.enabled = true
            return
        }
        val hDir = Vec3(dir.x, 0f, dir.z)
        val targetAlt = Math.toDegrees(atan2(dir.y.toDouble(), hDir.length.toDouble()))
        val useUp = targetAlt > 60
        val ahead = Yaw.dir(pose.yaw(groupYaw))
        val at = pose.position + ahead * 0.8f * cos(GUIDE_DOWN) + Vec3(0f, -0.8f * sin(GUIDE_DOWN), 0f)
        val zAxis = (pose.position - at).normalized()
        val arrow = if (useUp) up else straight
        val other = if (useUp) straight else up
        other?.enabled = false
        // the arrow points along the target direction projected on the arrow's plane
        var xAxis = if (useUp) Vec3.UP.rejectFrom(zAxis).normalized() else dir.rejectFrom(zAxis).let { if (it.length < 1e-3f) pose.right else it.normalized() }
        if (useUp && xAxis.length < 1e-3f) xAxis = pose.up
        val yAxis = zAxis.cross(xAxis).normalized()
        arrow?.setLocal(position = at, rotation = Quat4.fromBasis(xAxis, yAxis, zAxis), scale = Vec3(ARROW_M, ARROW_M, 1f))
        arrow?.enabled = true
    }

    private fun celestialLocal(f: SkyFrame, stageDir: Vec3): Vec3 {
        // positions under the celestial entity are in the equatorial frame
        val eq = f.toEquatorial(stageDir)
        return (eq * stageDir.length.toDouble()).f()
    }

    private fun facingCenter(f: SkyFrame, stageDir: Vec3): Quat4 {
        val u = f.toEquatorial(stageDir).normalized()
        val z = (u * -1.0).f()
        val ref = if (abs(u.z) > 0.99) Vec3(1f, 0f, 0f) else Vec3(0f, 0f, 1f)
        val x = ref.cross(z).normalized()
        val y = z.cross(x).normalized()
        return Quat4.fromBasis(x, y, z)
    }

    // ---------------------------------------------------------------- body card (design S06)

    private fun stepBodyCard(state: SkyUiState, now: Long) {
        val b = state.bodyCard
        val r = ring
        if (b == null) {
            if (state.hover !is Target.OfBody && state.guide?.arrivedAtMs == null) r?.enabled = false
            bodyCardAt = null
            return
        }
        val f = frame ?: return
        val bs = bodyStates.firstOrNull { it.body == b } ?: return
        val u = bs.eq.unit()
        val dirStage = f.toStage(u)
        if (r != null) {
            r.setLocal(position = (u * SkyGeometry.BODY_R).f(), rotation = facingCenter(f, dirStage), scale = Vec3(0.6f, 0.6f, 1f))
            r.enabled = true
        }
        if (bodyCardAt == null) {
            // along the line of sight of the pinch, pitch -10°..+30°, 0.9 m (design panels.planetCard)
            val fwd = pose.forward
            val yaw = Yaw.of(fwd)
            val pitch = atan2(fwd.y, sqrt(fwd.x * fwd.x + fwd.z * fwd.z)).coerceIn(Math.toRadians(-10.0).toFloat(), Math.toRadians(30.0).toFloat())
            val dir = Yaw.dir(yaw) * cos(pitch) + Vec3(0f, sin(pitch), 0f)
            val card = rig?.get(PanelIds.CARD)
            bodyCardAt =
                if (state.pulled != null && card != null && card.shown) card.position + Vec3(0f, (PanelSizes.CARD_H * CARD_K + PanelSizes.BODY_H * CARD_K) * DP_M / 2f + 0.04f, 0f)
                else pose.position + dir * PULL_M
            bodyCardFrom = (state.domeCenter ?: pose.position) + dirStage * 5.0f
            bodyCardStartMs = now
        }
        // closes when the sight has left both the card and the body for 2 s
        val at = bodyCardAt ?: return
        val onCard = angleDeg(pose.forward, (at - pose.position).normalized()) < 16f
        val onBody = angleDeg(pose.forward, dirStage) < 6f
        if (onCard || onBody) bodyAwaySinceMs = -1L else if (bodyAwaySinceMs < 0) bodyAwaySinceMs = now else if (now - bodyAwaySinceMs > BODY_AWAY_MS) {
            bodyAwaySinceMs = -1L
            sink(SkyEvent.BodyCardClosed)
        }
    }

    // ---------------------------------------------------------------- pulled model

    private fun stepModel(now: Long) {
        stepStick(now)
        model?.step(now)
        leaving?.let { if (!it.step(now)) { it.destroy(); leaving = null } }
    }

    /**
     * Per frame: either controller's thumbstick X turns the pulled-down model about its vertical axis (taste
     * profile D1). The rate follows the stick with a 0.12 s time constant; the turn adds to the drag turn.
     * The emulator idles at 2 - 4 fps, so the step is by wall-clock time, capped at 0.5 s a frame.
     */
    private fun stepStick(now: Long) {
        val dt = ((now - lastModelStepMs) / 1000f).coerceIn(0f, 0.5f)
        lastModelStepMs = now
        val m = model
        val x = if (now < debugStickUntilMs) debugStick else stick.x(now)
        val target = if (m != null && m.arrived(now) && turnDrag == null) StickTurn.rate(x) else 0f
        stickRate += (target - stickRate) * (dt / StickTurn.SMOOTH_S).coerceAtMost(1f)
        if (m == null) {
            stickRate = 0f
            stickActive = false
            return
        }
        if (target != 0f && !stickActive) {
            stickActive = true
            SkyLog.i(String.format(Locale.US, "STICK start x=%.2f", x))
        }
        m.yawOffset += stickRate * dt
        if (stickActive && target == 0f && abs(stickRate) < 0.02f) {
            stickActive = false
            stickRate = 0f
            SkyLog.i(String.format(Locale.US, "STICK end model_yaw=%.0f", Math.toDegrees(m.yawOffset.toDouble())))
        }
    }

    /** Debug `stick`: holds the thumbstick at [x] for [ms]. */
    fun debugStick(x: Float, ms: Long): String {
        debugStick = x.coerceIn(-1f, 1f)
        debugStickUntilMs = SystemClock.elapsedRealtime() + ms
        return "stick x=$debugStick ms=$ms model=${model != null} controller=${stick.status()}"
    }

    // ---------------------------------------------------------------- hand group (design handGroup)

    private fun stepHandGroup(state: SkyUiState, now: Long) {
        if (!hasHead) return
        val yaw = pose.yaw(groupYaw)
        val pitch = pose.pitch
        if (!groupSet) {
            groupYaw = yaw
            groupSet = true
        }
        val pitchDeg = Math.toDegrees(pitch.toDouble())
        // half-lying: average pitch above 35° for 3 s; back below 20° for 3 s
        if (pitchDeg > 35) { if (pitchHighSinceMs < 0) pitchHighSinceMs = now } else pitchHighSinceMs = -1L
        if (pitchDeg < 20) { if (pitchLowSinceMs < 0) pitchLowSinceMs = now } else pitchLowSinceMs = -1L
        if (!reclined && pitchHighSinceMs > 0 && now - pitchHighSinceMs > 3000) {
            reclined = true
            groupPitch = pitch
            SkyLog.i("FOLLOW reclined on")
        } else if (reclined && pitchLowSinceMs > 0 && now - pitchLowSinceMs > 3000) {
            reclined = false
            SkyLog.i("FOLLOW reclined off")
        }
        if (reclined && abs(pitch - groupPitch) > Math.toRadians(30.0)) groupPitch += (pitch - groupPitch) * 0.05f
        if (slideStartMs >= 0) {
            val q = ((now - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val e = if (q < 0.5f) 4f * q * q * q else 1f - (-2f * q + 2f).let { it * it * it } / 2f
            groupYaw = slideFrom + Yaw.wrap(slideTo - slideFrom) * e
            if (q >= 1f) slideStartMs = -1L
        } else if (pitchDeg < 60 || reclined) {
            val off = abs(Yaw.wrap(yaw - groupYaw))
            if (off > Math.toRadians(60.0) && dialDrag == null) {
                if (outSinceMs < 0) outSinceMs = now else if (now - outSinceMs > OUT_MS) {
                    slideFrom = groupYaw
                    slideTo = yaw
                    slideStartMs = now
                    outSinceMs = -1L
                    SkyLog.i(String.format(Locale.US, "FOLLOW slide from=%.0f to=%.0f", Math.toDegrees(slideFrom.toDouble()), Math.toDegrees(slideTo.toDouble())))
                }
            } else outSinceMs = -1L
        }
        // the dial
        val d = dialRoot ?: return
        val showDial = state.loaded && !state.calibrating
        d.enabled = showDial
        if (!showDial) return
        val dialPos = groupPoint(DIAL_RIGHT_DEG, DIAL_DOWN_DEG, DIAL_M)
        val toUser = (pose.position - dialPos).let { Vec3(it.x, 0f, it.z) }.normalized()
        val n = (Vec3.UP * cos(DIAL_TILT) + toUser * sin(DIAL_TILT)).normalized()
        val away = (-toUser).rejectFrom(n).normalized()
        val z = -away
        val x = n.cross(z).normalized()
        val rot = Quat4.fromBasis(x, n, z)
        d.setLocal(position = dialPos, rotation = rot)
        dialRotation = rot
        dialPosition = dialPos
        // handle at the displayed time of day, notch at "now"
        val shownMs = dialDrag?.liveMs ?: state.displayTime(SkyController.clock())
        val minute = SkyController.zoned(shownMs, state).let { it.hour * 60.0 + it.minute + it.second / 60.0 }
        val a = Dial.angleOf(minute)
        dialHandle?.setLocal(position = Vec3((-sin(a) * HANDLE_R).toFloat(), DIAL_T / 2 + 0.012f, (-cos(a) * HANDLE_R).toFloat()))
        val nowMin = SkyController.zoned(SkyController.clock(), state).let { it.hour * 60.0 + it.minute }
        val an = Dial.angleOf(nowMin)
        dialNotch?.bar(Vec3((-sin(an) * 0.102).toFloat(), DIAL_T / 2 + 0.002f, (-cos(an) * 0.102).toFloat()), Vec3((-sin(an) * 0.112).toFloat(), DIAL_T / 2 + 0.002f, (-cos(an) * 0.112).toFloat()), 0.006f)
        val m = mats
        if (m != null) dialIcon?.setMaterial(if (sunAlt > SkyFacts.DAY_SUN_ALT) m.sun else m.moon)
    }

    private var dialRotation = Quat4.IDENTITY
    private var dialPosition = Vec3.ZERO

    /** A point of the hand group: [rightDeg] to the right of its yaw, [downDeg] below (or below the gaze when reclined). */
    private fun groupPoint(rightDeg: Float, downDeg: Float, dist: Float): Vec3 {
        val yaw = groupYaw - Math.toRadians(rightDeg.toDouble()).toFloat()
        val basePitch = if (reclined) groupPitch + Math.toRadians(-30.0).toFloat() else 0f
        val pitch = basePitch + Math.toRadians(downDeg.toDouble()).toFloat() - (if (reclined) Math.toRadians(-32.0).toFloat() else 0f)
        val dir = Yaw.dir(yaw) * cos(pitch) + Vec3(0f, sin(pitch), 0f)
        return pose.position + dir * dist
    }

    private fun stepCalibration(state: SkyUiState, now: Long) {
        if (!hasHead) return
        val yaw = pose.yaw(calYaw)
        yawHistory.addLast(now to yaw)
        while (yawHistory.isNotEmpty() && now - yawHistory.first().first > 500) yawHistory.removeFirst()
        // the calibrate panel follows the head yaw hard (0.15 s smoothing, design handGroup.calibrate)
        calYaw += Yaw.wrap(yaw - calYaw) * min(1f, 16f / 150f)
        val on = state.calibrating && state.loaded
        floorRing?.enabled = on
        floorArrow?.enabled = on
        if (on) {
            val foot = Vec3(pose.position.x, 0.02f, pose.position.z)
            floorRing?.setLocal(position = foot, rotation = Quat4.axisAngle(Vec3.RIGHT, -(PI / 2).toFloat()), scale = Vec3(1f, 1f, 1f))
            val dir = Yaw.dir(yaw)
            floorArrow?.setLocal(position = foot + dir * 0.25f + Vec3(0f, 0.002f, 0f), rotation = flatArrow(dir), scale = Vec3(0.5f, 0.5f, 1f))
        }
    }

    /** A flat arrow lying on the floor, pointing along [dir]. */
    private fun flatArrow(dir: Vec3): Quat4 {
        val x = dir.normalized()
        val z = Vec3.UP
        val y = z.cross(x).normalized()
        return Quat4.fromBasis(x, y, z)
    }

    /** Median head yaw of the last 0.5 s (design T1). */
    fun calibrationYaw(): Float {
        if (yawHistory.isEmpty()) return pose.yaw(0f)
        val ref = yawHistory.last().second
        val sorted = yawHistory.map { ref + Yaw.wrap(it.second - ref) }.sorted()
        return Yaw.wrap(sorted[sorted.size / 2])
    }

    fun headPosition(): Vec3 = if (hasHead) pose.position else Vec3(0f, 1.6f, 0f)

    // ---------------------------------------------------------------- panels

    private fun placePanels(state: SkyUiState, now: Long) {
        val rig = rig ?: return
        if (!hasHead || !state.loaded) {
            for (id in PanelIds.pool) rig[id]?.hide()
            return
        }
        // hand bar or the calibrate panel
        if (state.calibrating) {
            rig[PanelIds.BAR]?.hide()
            val dir = Yaw.dir(calYaw) * cos(Math.toRadians(-26.0).toFloat()) + Vec3(0f, sin(Math.toRadians(-26.0).toFloat()), 0f)
            showAt(PanelIds.CAL, pose.position + dir * BAR_M)
        } else {
            rig[PanelIds.CAL]?.hide()
            showAt(PanelIds.BAR, groupPoint(0f, -32f, BAR_M))
        }
        val bar = rig[PanelIds.BAR]
        // popups above the hand bar
        val popupAt = groupPoint(0f, -6f, POPUP_M)
        for ((id, p) in listOf(PanelIds.TIME to Popup.TIME_PLACE, PanelIds.TONIGHT to Popup.TONIGHT, PanelIds.SETTINGS to Popup.SETTINGS)) {
            if (state.popup == p && !state.calibrating) showAt(id, popupAt) else rig[id]?.hide()
        }
        // toast: 4 cm above the hand bar (or above the calibrate panel)
        if (state.toast != null) {
            val anchor = (if (state.calibrating) rig[PanelIds.CAL] else bar)?.position ?: groupPoint(0f, -32f, BAR_M)
            showAt(PanelIds.TOAST, anchor + Vec3(0f, PanelSizes.BAR_H * BAR_K * DP_M / 2f + 0.04f + PanelSizes.TOAST_H * TOAST_K * DP_M / 2f, 0f))
        } else rig[PanelIds.TOAST]?.hide()
        // dial readout above the dial while dragging
        if (dialDrag != null) showAt(PanelIds.READOUT, dialPosition + Vec3(0f, 0.14f, 0f)) else rig[PanelIds.READOUT]?.hide()
        placeModelPanels(state, now)
        placeSkyTags(state)
    }

    private fun showAt(id: String, at: Vec3) {
        val slot = rig?.get(id) ?: return
        if (slot.shown) slot.moveTo(at) else slot.show(at)
    }

    private fun placeModelPanels(state: SkyUiState, now: Long) {
        val rig = rig ?: return
        val m = model
        if (m != null && m.arrived(now)) {
            val right = Yaw.dir(m.yaw - (PI / 2).toFloat())
            // on the box's front plane, its left edge 0.34 m right of the middle: seen from the viewer every edge and
            // corner of the box stays left of the card at any turn (a turned corner reaches 0.39 m but lies farther
            // back), so no edge crosses the card (QA q1-4)
            val toUser = -Yaw.dir(m.yaw)
            showAt(PanelIds.CARD, m.position + right * (MODEL_REACH + 0.05f + PanelSizes.CARD_W * CARD_K * DP_M / 2f) + toUser * MODEL_HALF)
            if (m.depthShown > 0.5f) {
                showAt(PanelIds.EYE, m.eyePoint() + Vec3(0f, 0.035f, 0f))
                for (i in 0 until PanelIds.TICKS) showAt(PanelIds.tick(i), m.tickPoint(i) + Vec3(0f, -0.03f, 0f))
            } else {
                rig[PanelIds.EYE]?.hide()
                for (i in 0 until PanelIds.TICKS) rig[PanelIds.tick(i)]?.hide()
            }
        } else {
            rig[PanelIds.CARD]?.hide()
            rig[PanelIds.EYE]?.hide()
            for (i in 0 until PanelIds.TICKS) rig[PanelIds.tick(i)]?.hide()
        }
        val target = bodyCardAt
        if (state.bodyCard != null && target != null) {
            val q = ((now - bodyCardStartMs) / FLY_BODY_MS).coerceIn(0f, 1f)
            val e = 1f - (1f - q) * (1f - q) * (1f - q)
            val from = bodyCardFrom ?: target
            showAt(PanelIds.BODY, from.lerp(target, e))
        } else rig[PanelIds.BODY]?.hide()
    }

    private fun placeSkyTags(state: SkyUiState) {
        val rig = rig ?: return
        val f = frame
        val cat = SkyController.catalog
        val center = state.domeCenter ?: pose.position
        val skyOn = f != null && cat != null && !state.calibrating
        // compass tags on the horizon (+1.5°)
        for (i in 0 until PanelIds.COMPASS_TAGS) {
            if (skyOn && state.layer(Layer.COMPASS)) {
                val az = Math.toRadians(i * 90.0)
                val alt = Math.toRadians(2.0)
                showAt(PanelIds.compass(i), center + f!!.local(cos(alt) * cos(az), cos(alt) * sin(az), sin(alt)) * TAG_R)
            } else rig[PanelIds.compass(i)]?.hide()
        }
        // body tags under the moon and the planets above the horizon
        for ((i, b) in PanelIds.TAG_BODIES.withIndex()) {
            val bs = bodyStates.firstOrNull { it.body == b }
            if (skyOn && bs != null && state.layer(Layer.BODIES) && f!!.sinAlt(bs.eq.unit()) > 0) {
                val dir = f.toStage(bs.eq.unit())
                val h = f.horizontalOf(dir)
                val below = Math.toRadians(h.altDeg - 2.4)
                val az = Math.toRadians(h.azDeg)
                showAt(PanelIds.bodyTag(i), center + f.local(cos(below) * cos(az), cos(below) * sin(az), sin(below)) * TAG_R)
            } else rig[PanelIds.bodyTag(i)]?.hide()
        }
        // the name over the looked-at constellation (or the guide target once reached)
        val named = (state.hover as? Target.Constellation)?.index ?: (state.guide?.takeIf { it.arrivedAtMs != null }?.target as? Target.Constellation)?.index
        if (skyOn && named != null) {
            val c = cat!!.constellations[named]
            val dir = f!!.toStage(c.centre.unit())
            val h = f.horizontalOf(dir)
            val alt = Math.toRadians(min(h.altDeg + 4.0, 86.0))
            val az = Math.toRadians(h.azDeg)
            showAt(PanelIds.HOVER, center + f.local(cos(alt) * cos(az), cos(alt) * sin(az), sin(alt)) * TAG_R)
        } else rig[PanelIds.HOVER]?.hide()
    }

    // =========================================================================================
    // input: system taps and drags
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val now = SystemClock.elapsedRealtime()
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        if (lastDragEndMs > 0 && now - lastDragEndMs < TAP_AFTER_DRAG_MS) {
            SkyLog.i("TAP ignored reason=after-drag target=$name")
            return
        }
        SkyLog.i("TAP target=$name stage=${SkyLog.pos(stage)}")
        val state = ui
        if (state.calibrating || !built) return
        if (state.popup != null) {
            sink(SkyEvent.PopupDismissed)
            return
        }
        if (name == "dialHandle" || name.startsWith("pulled")) return
        sink(SkyEvent.Pinched(resolvePinch(target, stage)))
    }

    /** Hit priority (design stateModel.hitPriority): the lit object, else the body hit, else the nearest figure within 4°. */
    private fun resolvePinch(target: Entity?, stage: Vec3?): Pinch {
        when (val h = ui.hover) {
            is Target.Constellation -> return Pinch.OnConstellation(h.index)
            is Target.OfBody -> return Pinch.OnBody(h.body)
            null -> Unit
        }
        val cat = SkyController.catalog ?: return Pinch.Empty
        val f = frame ?: return Pinch.Empty
        for ((b, v) in bodies) if (target != null && v.owns(target)) return Pinch.OnBody(b)
        val center = ui.domeCenter ?: pose.position
        val dir = if (stage != null && target != null && (stage - center).length > 1f) (stage - center).normalized() else (debugGaze ?: pose.forward)
        val found = targetAt(cat, f, f.toEquatorial(dir), ENTER_RAD, null, ui.layer(Layer.BODIES))
        SkyLog.i("TAP resolve dir=${SkyLog.pos(dir)} found=${found?.let { describe(cat, it) }}")
        return when (found) {
            is Target.Constellation -> Pinch.OnConstellation(found.index)
            is Target.OfBody -> Pinch.OnBody(found.body)
            null -> Pinch.Empty
        }
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            "start" -> {
                val target = value.targetEntity
                val name = runCatching { target?.getName() }.getOrNull() ?: "none"
                SkyLog.i("DRAG start target=$name")
                if (name == "dialHandle" && !ui.calibrating) startDial() else if (name.startsWith("pulled") && model != null) turnDrag = TurnDrag(model!!.yawOffset)
            }
            "move" -> {
                val a = value.dragAmount
                dialDrag?.let { d ->
                    d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
                    moveDial(d, handDelta(d.totalPx) ?: return, now)
                }
                turnDrag?.let { d ->
                    d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
                    val hand = handDelta(d.totalPx) ?: return
                    val m = model ?: return
                    val right = Yaw.dir(m.yaw - (PI / 2).toFloat())
                    // 1 cm = 6° (design T5)
                    m.yawOffset = d.startYaw + Math.toRadians(hand.dot(right) * 600.0).toFloat()
                }
            }
            "end", "cancel" -> {
                lastDragEndMs = now
                dialDrag?.let { endDial(it) }
                dialDrag = null
                if (turnDrag != null) SkyLog.i("DRAG turn end yaw=${String.format(Locale.US, "%.0f", Math.toDegrees((model?.yawOffset ?: 0f).toDouble()))}")
                turnDrag = null
            }
        }
    }

    private fun startDial() {
        val startMs = ui.displayTime(SkyController.clock())
        val minute = SkyController.zoned(startMs).let { it.hour * 60.0 + it.minute + it.second / 60.0 }
        val a = Dial.angleOf(minute)
        val d = DialDrag(startMs, Vec3((-sin(a) * HANDLE_R).toFloat(), 0f, (-cos(a) * HANDLE_R).toFloat()))
        d.prevMinute = minute
        dialDrag = d
        sink(SkyEvent.DialStarted)
        SkyLog.i("DIAL start time=${SkyController.zoned(startMs)}")
    }

    private fun moveDial(d: DialDrag, hand: Vec3, now: Long) {
        // the hand's move in the dial plane, from the handle where it was grabbed
        val local = dialRotation.conjugate().rotate(hand)
        val p = d.handleLocal + Vec3(local.x, 0f, local.z)
        if (p.length < 0.02f) return
        val angle = atan2(-p.x.toDouble(), -p.z.toDouble())
        val minute = Dial.minuteOf(angle)
        d.accumulated += Dial.deltaMinutes(d.prevMinute, minute)
        d.prevMinute = minute
        d.liveMs = snapDial(d.startTimeMs + (d.accumulated * 60_000L).toLong())
        if (now - d.lastSentMs >= 100) {
            d.lastSentMs = now
            dialReadout["delta"] = d.liveMs - d.startTimeMs
            dialReadout["time"] = d.liveMs
            sink(SkyEvent.DialMoved(d.liveMs))
        }
    }

    private fun snapDial(ms: Long): Long {
        val now = SkyController.clock()
        if (abs(ms - now) <= Dial.NOW_SNAP_MIN * 60_000L) return now
        val step = Dial.SNAP_MIN * 60_000L
        return Math.round(ms.toDouble() / step) * step
    }

    private fun endDial(d: DialDrag) {
        SkyLog.i("DIAL end time=${SkyController.zoned(d.liveMs)} delta_min=${(d.liveMs - d.startTimeMs) / 60_000L}")
        dialReadout.clear()
        sink(SkyEvent.DialEnded(d.liveMs))
    }

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // debug seams
    // =========================================================================================

    /** Debug: a virtual line of sight (Stage direction) for hover and pinch; null = the head. */
    var debugGaze: Vec3? = null

    /** Debug: aim the virtual sight at an altitude / azimuth (degrees) of the current sky frame. */
    fun debugAim(azDeg: Double, altDeg: Double): String {
        val f = frame ?: return "no sky"
        val az = Math.toRadians(azDeg)
        val alt = Math.toRadians(altDeg)
        debugGaze = f.local(cos(alt) * cos(az), cos(alt) * sin(az), sin(alt))
        return "gaze=${SkyLog.pos(debugGaze)}"
    }

    /** Debug: where a constellation / body is now (alt, az) and its Stage direction. */
    fun debugWhere(name: String): String {
        val cat = SkyController.catalog ?: return "no data"
        val f = frame ?: return "no sky"
        val c = cat.constellation(name)
        val u = c?.centre?.unit() ?: Body.of(name.lowercase(Locale.US))?.let { b -> bodyStates.firstOrNull { it.body == b }?.eq?.unit() } ?: return "unknown $name"
        val d = f.toStage(u)
        val h = f.horizontalOf(d)
        return String.format(Locale.US, "WHERE %s alt=%.2f az=%.2f dir=%s head_yaw=%.1f", name, h.altDeg, h.azDeg, SkyLog.pos(d), Math.toDegrees(pose.yaw(0f).toDouble()))
    }

    /** Debug: simulates a dial drag to [timeMs] over [ms] (readout and S07 visible); [hold] keeps it in S07. */
    fun debugDial(toMs: Long, ms: Long, hold: Boolean): String {
        startDial()
        val d = dialDrag ?: return "no dial"
        scope?.launch {
            val t0 = SystemClock.elapsedRealtime()
            while (true) {
                val q = ((SystemClock.elapsedRealtime() - t0).toFloat() / ms).coerceIn(0f, 1f)
                d.liveMs = snapDial(d.startTimeMs + ((toMs - d.startTimeMs) * q).toLong())
                dialReadout["delta"] = d.liveMs - d.startTimeMs
                dialReadout["time"] = d.liveMs
                sink(SkyEvent.DialMoved(d.liveMs))
                if (q >= 1f) break
                delay(100)
            }
            if (!hold) {
                endDial(d)
                dialDrag = null
            }
        }
        return "dial to ${SkyController.zoned(toMs)} hold=$hold"
    }

    fun debugDialRelease(): String {
        val d = dialDrag ?: return "no drag"
        endDial(d)
        dialDrag = null
        return "released"
    }

    fun debugTurnModel(deg: Float): String {
        val m = model ?: return "no model"
        m.yawOffset = Math.toRadians(deg.toDouble()).toFloat()
        return "model yaw=$deg"
    }

    fun poseLine(): String = String.format(Locale.US, "POSE hmd pos=%s %s fwd=%s yaw=%.1f pitch=%.1f valid=%s frames=%d", SkyLog.pos(pose.position), SkyLog.quat(pose.rotation), SkyLog.pos(pose.forward), Math.toDegrees(pose.yaw(0f).toDouble()), Math.toDegrees(pose.pitch.toDouble()), hasHead, frames)

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    fun parkHandGroup() {
        groupSet = false
        slideStartMs = -1L
    }

    companion object {
        const val DOME_R = 6.0f
        const val TAG_R = 5.6f
        const val BAR_K = 0.76f
        const val POPUP_K = 0.77f
        const val CARD_K = 0.76f
        const val SKY_TAG_K = 3.75f
        const val READOUT_K = 0.6f
        const val TOAST_K = 0.6f
        const val MODEL_TAG_K = 0.35f
        const val SKY_PITCH = 1.55f
        const val BAR_M = 0.70f
        const val POPUP_M = 0.75f
        const val DIAL_M = 0.55f
        const val DIAL_D = 0.22f
        const val DIAL_RIGHT_DEG = 31f
        const val DIAL_DOWN_DEG = -44f
        const val DIAL_T = 0.015f
        const val HANDLE_R = 0.094
        val DIAL_TILT = Math.toRadians(50.0).toFloat()
        const val PULL_M = 0.9f
        const val MODEL_HALF = 0.275f
        const val MODEL_REACH = 0.29f
        const val LONGEST_M = 0.5
        const val ARROW_M = 0.12f
        val GUIDE_DOWN = Math.toRadians(24.0).toFloat()
        const val ARRIVE_DEG = 12f
        val ENTER_RAD = Math.toRadians(4.0)
        val EXIT_RAD = Math.toRadians(6.0)
        val BODY_RAD = Math.toRadians(3.0)
        const val DWELL_MS = 300L
        const val OUT_MS = 800L
        const val SLIDE_MS = 600f
        const val FLY_BODY_MS = 400f
        const val FADE_IN_MS = 600f
        const val BODY_AWAY_MS = 2000L
        const val CLIP_SKY_MS = 60_000L
        const val CLIP_MOVING_MS = 100L
        const val TAP_AFTER_DRAG_MS = 300L
        const val LOG_MS = 5_000L

        fun angleDeg(a: Vec3, b: Vec3): Float = Math.toDegrees(acos(a.normalized().dot(b.normalized()).coerceIn(-1f, 1f).toDouble())).toFloat()
    }
}

/** Sun, moon or planet on the celestial sphere: a disc facing the centre, a 3° tap sphere; the moon shows its phase. */
internal class BodyView(val body: Body, parent: Entity, disc: MeshResource, m: SkyMaterials, shared: SharedResources) {
    private val root = Entity().named("body:${body.id}")
    private val face: ModelEntity
    private var lit: ModelEntity? = null
    private var litMesh: MeshResource? = null
    private val collider = Entity().named("bodyHit:${body.id}")
    private var litFraction = -1.0
    private var unit: Vec3d = Vec3d(1.0, 0.0, 0.0)
    private var shown = true

    init {
        parent.addChild(root)
        val size = if (body == Body.SUN || body == Body.MOON) 0.20f else 0.07f
        val mat =
            when (body) {
                Body.SUN -> m.sun
                Body.MOON -> m.moonDark
                else -> m.planets.getValue(body.id)
            }
        face = model(disc, mat, "bodyFace:${body.id}", root).also { it.setLocal(scale = Vec3(size, size, 1f)) }
        if (body == Body.MOON) {
            val lm = shared.keep(SkyGeometry.moonLit(0.5).build("moonLit"))
            litMesh = lm
            lit = model(lm, m.moon, "moonLit", root).also { it.setLocal(scale = Vec3(size, size, 1f)) }
        }
        runCatching {
            collider.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createSphere(0.30f)), physicsMaterial = PhysicsMaterialResource()))
            collider.components.set(InteractableComponent())
        }
        root.addChild(collider)
    }

    fun owns(e: Entity): Boolean = e === collider || e === face || e === lit || e === root

    fun update(s: BodyState, sun: BodyState, f: SkyFrame, layerOn: Boolean) {
        unit = s.eq.unit()
        val u = unit
        val z = (u * -1.0).f()
        var x: Vec3
        if (body == Body.MOON) {
            // +X of the disc towards the sun, in the plane of the sky
            val toSun = sun.eq.unit()
            val proj = toSun - u * toSun.dot(u)
            x = if (proj.length < 1e-6) Vec3(1f, 0f, 0f).rejectFrom(z).normalized() else proj.normalized().f()
            if (abs(s.illuminated - litFraction) > 0.01) {
                litFraction = s.illuminated
                litMesh?.let { SkyGeometry.moonLit(s.illuminated).replace(it) }
            }
        } else {
            val ref = if (abs(u.z) > 0.99) Vec3(1f, 0f, 0f) else Vec3(0f, 0f, 1f)
            x = ref.cross(z).normalized()
        }
        // seen from the centre (looking along -Z of the disc) the lit side has to be on the sun side: Z points in
        val y = z.cross(x).normalized()
        root.setLocal(position = (u * SkyGeometry.BODY_R).f(), rotation = Quat4.fromBasis(x, y, z))
        setVisible(f, layerOn)
    }

    fun setVisible(f: SkyFrame, layerOn: Boolean) {
        val on = layerOn && f.sinAlt(unit) > 0
        if (on != shown) {
            root.enabled = on
            shown = on
        }
    }
}

/**
 * The pulled-down constellation (design entities3d.pulledModel): glowing balls, thin bars, a faint glass box;
 * flies down from the sky in 0.6 s, spreads out to true distance in 0.8 s, turns about the vertical.
 */
internal class PulledModel(
    val constellation: Int,
    private val layout: List<ModelStar>,
    catalog: SkyCatalog,
    parent: Entity,
    m: SkyMaterials,
    ball: MeshResource,
    cube: MeshResource,
    disc: MeshResource,
) {
    private val root = Entity().named("pulledRoot")
    private val turn = Entity().named("pulledTurn")
    private val balls = ArrayList<Entity>()
    private val bars = ArrayList<Triple<Entity, Int, Int>>()
    private val index = HashMap<Int, Int>()
    private val eye: Entity
    private val ticks = ArrayList<Entity>()
    private var from = Vec3.ZERO
    private var to = Vec3.ZERO
    private var fromScale = 1f
    private var startMs = -1L
    private var leaving = false
    private var fadeOnly = false
    private var leaveMs = -1L
    private var depthTarget = 0f
    private var depthFrom = 0f
    private var depthStartMs = -1L
    var depthShown = 0f
        private set
    var yaw = 0f
        private set
    var yawOffset = 0f
    var position = Vec3.ZERO
        private set

    init {
        parent.addChild(root)
        root.addChild(turn)
        val hit = Entity().named("pulledHit")
        runCatching {
            hit.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(0.55f, 0.55f, 0.55f))), physicsMaterial = PhysicsMaterialResource()))
            hit.components.set(InteractableComponent())
        }
        turn.addChild(hit)
        val h = 0.275f
        val corners = listOf(-h, h)
        for (a in corners) for (b in corners) {
            model(cube, m.glassEdge, "pulledEdge", turn).bar(Vec3(-h, a, b), Vec3(h, a, b), 0.0015f)
            model(cube, m.glassEdge, "pulledEdge", turn).bar(Vec3(a, -h, b), Vec3(a, h, b), 0.0015f)
            model(cube, m.glassEdge, "pulledEdge", turn).bar(Vec3(a, b, -h), Vec3(a, b, h), 0.0015f)
        }
        layout.forEachIndexed { i, s ->
            index[s.starIndex] = i
            val star = catalog.stars[s.starIndex]
            val mat = m.starBalls[StarLook.colorClass(star.bv)]
            balls += model(ball, mat, "pulledStar", turn).also { it.setLocal(position = Vec3(s.x, s.y, 0f), scale = Vec3(s.size, s.size, s.size)) }
        }
        val c = catalog.constellations[constellation]
        for (si in c.segments) {
            val seg = catalog.segments[si]
            val a = index[seg.a] ?: continue
            val b = index[seg.b] ?: continue
            bars += Triple(model(cube, m.whiteSoft, "pulledLine", turn), a, b)
        }
        // "you are here" eye in front, the 10 / 100 / 1000 ly ticks along the bottom edge
        eye = model(disc, m.amber, "pulledEye", turn).also { it.setLocal(position = Vec3(0f, -h, h + 0.06f), scale = Vec3(0.018f, 0.018f, 1f)) }
        for (ly in listOf(10f, 100f, 1000f)) {
            val z = h - io.embodit.skydome.domain.DepthScale.depth(ly)
            ticks += model(cube, m.white, "pulledTick", turn).also { it.bar(Vec3(-h, -h, z), Vec3(-h + 0.03f, -h, z), 0.002f) }
        }
        model(cube, m.whiteSoft, "pulledScale", turn).bar(Vec3(-h, -h, h), Vec3(-h, -h, -h), 0.0015f)
        layoutBars()
        setDepthVisible(false)
    }

    fun arrive(skyPoint: Vec3, at: Vec3, yaw: Float, skyScale: Float) {
        from = skyPoint
        to = at
        fromScale = skyScale
        this.yaw = yaw
        startMs = SystemClock.elapsedRealtime()
        position = to
        place(0f)
    }

    fun leave(skyPoint: Vec3, aboveHorizon: Boolean) {
        leaving = true
        fadeOnly = !aboveHorizon
        from = position
        to = skyPoint
        leaveMs = SystemClock.elapsedRealtime()
    }

    fun arrived(now: Long): Boolean = !leaving && startMs >= 0 && now - startMs >= ARRIVE_MS

    private fun place(q: Float) {
        val e = 1f - (1f - q) * (1f - q) * (1f - q)
        val p = from.lerp(to, e)
        val k = fromScale + (1f - fromScale) * e
        root.setLocal(position = p, rotation = Quat4.axisAngle(Vec3.UP, yaw), scale = Vec3(k, k, k))
    }

    /** Returns false once a leaving model is gone. */
    fun step(now: Long): Boolean {
        if (leaving) {
            if (fadeOnly) {
                val q = ((now - leaveMs) / FADE_MS).coerceIn(0f, 1f)
                root.setOpacity(1f - q)
                return q < 1f
            }
            val q = ((now - leaveMs) / LEAVE_MS).coerceIn(0f, 1f)
            val e = q * q
            val p = from.lerp(to, e)
            val k = 1f + (fromScale - 1f) * e
            root.setLocal(position = p, scale = Vec3(k, k, k))
            if (q > 0.7f) root.setOpacity(1f - (q - 0.7f) / 0.3f)
            return q < 1f
        }
        if (startMs >= 0) {
            val q = ((now - startMs) / ARRIVE_MS).coerceIn(0f, 1f)
            place(q)
        }
        turn.setLocal(rotation = Quat4.axisAngle(Vec3.UP, yawOffset))
        if (depthStartMs >= 0) {
            val q = ((now - depthStartMs) / DEPTH_MS).coerceIn(0f, 1f)
            val e = if (q < 0.5f) 2 * q * q else 1 - (-2 * q + 2) * (-2 * q + 2) / 2
            depthShown = depthFrom + (depthTarget - depthFrom) * e
            layout.forEachIndexed { i, s -> balls[i].setLocal(position = Vec3(s.x, s.y, (0.25f - s.depth) * depthShown)) }
            layoutBars()
            if (q >= 1f) {
                depthStartMs = -1L
                setDepthVisible(depthTarget > 0.5f)
            }
        }
        return true
    }

    fun setDepth(on: Boolean) {
        val target = if (on) 1f else 0f
        if (target == depthTarget) return
        depthFrom = depthShown
        depthTarget = target
        depthStartMs = SystemClock.elapsedRealtime()
        if (on) setDepthVisible(true)
        SkyLog.i("PULL depth=$on")
    }

    private fun setDepthVisible(on: Boolean) {
        eye.enabled = on
        ticks.forEach { it.enabled = on }
    }

    private fun layoutBars() {
        for ((e, a, b) in bars) {
            val pa = pos(a)
            val pb = pos(b)
            val d = pb - pa
            val len = d.length
            if (len < 1e-4f) continue
            val dir = d / len
            val ra = layout[a].size / 2f
            val rb = layout[b].size / 2f
            e.bar(pa + dir * ra, pb - dir * rb, 0.003f)
        }
    }

    private fun pos(i: Int): Vec3 {
        val s = layout[i]
        return Vec3(s.x, s.y, (0.25f - s.depth) * depthShown)
    }

    /** Stage point of the eye marker (for its tag). */
    fun eyePoint(): Vec3 = toStage(Vec3(0f, -0.275f, 0.335f))

    fun tickPoint(i: Int): Vec3 {
        val ly = floatArrayOf(10f, 100f, 1000f)[i]
        return toStage(Vec3(-0.275f, -0.275f, 0.275f - io.embodit.skydome.domain.DepthScale.depth(ly)))
    }

    private fun toStage(local: Vec3): Vec3 = position + Quat4.axisAngle(Vec3.UP, yaw + yawOffset).rotate(local)

    fun destroy() {
        runCatching { root.destroy(true) }
    }

    companion object {
        const val ARRIVE_MS = 600f
        const val LEAVE_MS = 500f
        const val FADE_MS = 300f
        const val DEPTH_MS = 800f
    }
}
