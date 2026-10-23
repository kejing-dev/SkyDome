package io.embodit.skydome.ui.sky

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import io.embodit.skydome.domain.Astro
import io.embodit.skydome.domain.Body
import io.embodit.skydome.domain.City
import io.embodit.skydome.domain.Constellation
import io.embodit.skydome.domain.Pick
import io.embodit.skydome.domain.SkyCatalog
import io.embodit.skydome.domain.SkyFacts
import io.embodit.skydome.domain.Yaw
import io.embodit.skydome.platform.SkyLog
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All behaviour of the app (design `states`, `interactions`). One per process, so the state outlives a
 * re-opened Stage: "全黑" re-opens the Stage with another style and the time, place, heading and the
 * pulled-down constellation stay (design `container.fullSwitch`). Main thread only.
 */
object SkyController {
    private val _state = MutableStateFlow(SkyUiState())
    val state: StateFlow<SkyUiState> = _state.asStateFlow()

    /** One consumer per effect: both Stages' screens collect, whichever is first handles it (a Channel, not a broadcast). */
    private val _effects = Channel<SkyEffect>(Channel.BUFFERED)
    val effects: Flow<SkyEffect> = _effects.receiveAsFlow()

    /**
     * A pending Stage switch (true = to Full). Both Stages' screens can be composed at once while switching, and a
     * navigator of the Stage that is not open answers NotAllowed: only the screen of [currentStageId] handles it.
     */
    val stageRequest = MutableStateFlow<Boolean?>(null)

    /** The Stage that is open now ("SkyDomeStage" Mixed at start, "SkyDomeFull" after a switch). */
    @Volatile var currentStageId: String = MIXED_STAGE_ID

    @Volatile var catalog: SkyCatalog? = null
        private set

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var toastSeq = 0L
    private var started = false

    /** Debug: the guide does not time out or end by itself (screenshots of the arrow / the arrival ring). */
    var holdGuide = false

    /** Debug: the next switch to Full fails (S13). */
    var failNextStage = false

    /** Wall clock source (debug can pin it). */
    var clock: () -> Long = { System.currentTimeMillis() }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val p = app.getSharedPreferences("sky", Context.MODE_PRIVATE)
        prefs = p
        val zone = TimeZone.getDefault().id
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val cat = withContext(Dispatchers.IO) { SkyCatalog.load { name -> app.assets.open(name) } }
            catalog = cat
            val saved = p.getString("city", null)?.let { cat.city(it) }
            val byZone = cat.cities.firstOrNull { it.zone == zone } ?: cat.cities.firstOrNull { ZoneId.of(it.zone).rules == ZoneId.of(zone).rules }
            val city = saved ?: byZone ?: cat.cities.first()
            val layers = Layer.entries.filter { p.getBoolean("layer_${it.tag}", true) }.toSet()
            val language = LanguagePref.entries.firstOrNull { it.tag == p.getString("language", null) } ?: LanguagePref.SYSTEM
            _state.update {
                it.copy(
                    loaded = true,
                    cityId = city.id,
                    cityFallback = saved == null && byZone == null,
                    layers = layers,
                    language = language,
                    tickMs = clock(),
                )
            }
            SkyLog.i("DATA loaded stars=${cat.stars.size} segments=${cat.segments.size} constellations=${cat.constellations.size} ms=${SystemClock.elapsedRealtime() - t0} city=${city.id} zone=$zone")
            logState("load")
        }
        startTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker =
            scope.launch {
                while (true) {
                    val s = _state.value
                    delay(if (s.fast) 250L else 1000L)
                    onEvent(SkyEvent.Tick)
                }
            }
    }

    fun city(s: SkyUiState = _state.value): City? = catalog?.city(s.cityId)

    fun zone(s: SkyUiState = _state.value): ZoneId = runCatching { ZoneId.of(city(s)?.zone ?: "Asia/Shanghai") }.getOrDefault(ZoneId.of("Asia/Shanghai"))

    fun zoned(ms: Long, s: SkyUiState = _state.value): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone(s))

    /** Is it day (sun above -6°) at the displayed time? (M4) */
    fun isDay(s: SkyUiState = _state.value): Boolean {
        val c = city(s) ?: return false
        return Astro.sunAltitude(s.displayTime(clock()), c.lat, c.lon) > SkyFacts.DAY_SUN_ALT
    }

    /** The QA name: base state + modifiers, e.g. "S02+M1+M4". */
    fun designState(s: SkyUiState = _state.value): String {
        s.debugState?.let { return it }
        val mods = ArrayList<String>()
        if (s.demoHeading) mods += "M1"
        if (!s.live && !s.fast) mods += "M2"
        if (s.fast) mods += "M3"
        if (s.loaded && isDay(s)) mods += "M4"
        if (s.layers.size < Layer.entries.size) mods += "M5"
        if (s.fullDark) mods += "M6"
        if (s.headingStale) mods += "M7"
        return (listOf(s.baseState) + mods).joinToString("+")
    }

    private var lastLogged = ""

    private fun logState(why: String) {
        val s = _state.value
        val name = designState(s)
        if (name == lastLogged && why == "tick") return
        lastLogged = name
        SkyLog.i("STATE $name why=$why time=${zoned(s.displayTime(clock()), s)} city=${s.cityId} pulled=${s.pulled?.let { catalog?.constellations?.get(it)?.abbr }} depth=${s.depth} hover=${s.hover} popup=${s.popup} north=${String.format(java.util.Locale.US, "%.1f", Math.toDegrees(s.northYaw.toDouble()))}")
    }

    private fun toast(kind: ToastKind): Toast = Toast(kind, ++toastSeq).also { t ->
        scope.launch {
            delay(TOAST_MS)
            onEvent(SkyEvent.ToastExpired(t.id))
        }
    }

    private fun set(why: String, f: (SkyUiState) -> SkyUiState) {
        _state.update(f)
        logState(why)
    }

    /** Freezes the displayed time where it is (leaves live / fast) and returns the new state. */
    private fun SkyUiState.frozen(): SkyUiState {
        val now = clock()
        return copy(live = false, timeBaseMs = displayTime(now), fastSinceWallMs = null)
    }

    /** Sets a displayed time; within a minute of the clock it goes back to live. */
    private fun SkyUiState.at(timeMs: Long): SkyUiState {
        val now = clock()
        return if (kotlin.math.abs(timeMs - now) <= 60_000L) copy(live = true, fastSinceWallMs = null, timeBaseMs = now) else copy(live = false, fastSinceWallMs = null, timeBaseMs = timeMs)
    }

    fun setTime(timeMs: Long) = set("setTime") { it.at(timeMs) }

    fun onEvent(e: SkyEvent) {
        val s = _state.value
        when (e) {
            SkyEvent.Tick -> {
                _state.update { it.copy(tickMs = clock()) }
                val g = s.guide
                if (g != null && !holdGuide && g.arrivedAtMs == null && clock() - g.startedAtMs > GUIDE_TIMEOUT_MS) set("guide-timeout") { it.copy(guide = null) }
                logState("tick")
            }
            SkyEvent.SceneReady -> logState("scene-ready")
            is SkyEvent.CalibrateChoice -> set("calibrate-choice") { it.copy(calibrateChoice = e.direction.coerceIn(0, 3)) }
            is SkyEvent.CalibrateConfirmed -> {
                // facing east means north is a quarter turn to the left (+yaw)
                val north = Yaw.wrap(e.headYaw + s.calibrateChoice * (Math.PI / 2).toFloat())
                set("calibrated") { it.copy(calibrating = false, everCalibrated = true, demoHeading = false, headingStale = false, northYaw = north, domeCenter = e.head) }
                _effects.trySend(SkyEffect.Feedback("confirm"))
            }
            is SkyEvent.CalibrateSkipped -> {
                if (s.everCalibrated) {
                    set("calibrate-skip-keep") { it.copy(calibrating = false) }
                } else {
                    set("calibrate-skip") { it.copy(calibrating = false, demoHeading = true, northYaw = e.headYaw, domeCenter = it.domeCenter ?: e.head, toast = toast(ToastKind.DEMO_HEADING)) }
                }
            }
            SkyEvent.Recalibrate -> set("recalibrate") { it.copy(calibrating = true, popup = null, calibrateChoice = 0, hover = null, guide = null, bodyCard = null) }
            SkyEvent.Recentered -> set("recentered") { it.copy(headingStale = true) }
            SkyEvent.TimeTapped -> set("popup-time") { it.stopFast().copy(popup = if (it.popup == Popup.TIME_PLACE) null else Popup.TIME_PLACE, hover = null) }
            SkyEvent.TonightTapped -> set("popup-tonight") { it.stopFast().copy(popup = if (it.popup == Popup.TONIGHT) null else Popup.TONIGHT, hover = null) }
            SkyEvent.SettingsTapped -> set("popup-settings") { it.stopFast().copy(popup = if (it.popup == Popup.SETTINGS) null else Popup.SETTINGS, hover = null) }
            SkyEvent.PopupDismissed -> if (s.popup != null) set("popup-close") { it.copy(popup = null) }
            SkyEvent.FastToggled -> set(if (s.fast) "fast-stop" else "fast-start") { if (it.fast) it.frozen() else it.copy(live = false, timeBaseMs = it.displayTime(clock()), fastSinceWallMs = clock(), hover = null, popup = null) }
            SkyEvent.BackToNow -> set("back-to-now") { it.copy(live = true, fastSinceWallMs = null, timeBaseMs = clock(), popup = if (it.popup == Popup.TIME_PLACE) null else it.popup, toast = if (it.live) it.toast else toast(ToastKind.BACK_TO_NOW)) }
            SkyEvent.ToTonight -> {
                val c = city(s) ?: return
                val dark = SkyFacts.nextDark(s.displayTime(clock()), c.lat, c.lon) ?: return
                set("to-tonight") { it.at(dark) }
            }
            is SkyEvent.DateStep -> set("date-step") { it.at(it.displayTime(clock()) + e.days * DAY_MS) }
            SkyEvent.Today -> {
                // same clock time today, in the city's zone
                val shown = zoned(s.displayTime(clock()), s)
                val today = zoned(clock(), s).toLocalDate()
                val t = shown.with(today).toInstant().toEpochMilli()
                set("today") { it.at(t) }
            }
            is SkyEvent.CitySelected -> {
                prefs?.edit()?.putString("city", e.id)?.apply()
                set("city") { it.copy(cityId = e.id, cityFallback = false, popup = null) }
            }
            is SkyEvent.LayerToggled -> {
                prefs?.edit()?.putBoolean("layer_${e.layer.tag}", e.on)?.apply()
                set("layer") { st -> st.copy(layers = if (e.on) st.layers + e.layer else st.layers - e.layer, bodyCard = if (e.layer == Layer.BODIES && !e.on) null else st.bodyCard) }
            }
            is SkyEvent.FullDarkToggled -> {
                if (e.on == s.fullDark) return
                if (e.on && failNextStage) {
                    failNextStage = false
                    SkyLog.i("STAGE full refused reason=debug-failStage")
                    set("full-failed") { it.copy(toast = toast(ToastKind.SWITCH_FAILED)) }
                    return
                }
                stageRequest.value = e.on
            }
            is SkyEvent.StageSwitched -> {
                if (e.ok) currentStageId = if (e.full) FULL_STAGE_ID else MIXED_STAGE_ID
                if (e.ok) set("stage-${if (e.full) "full" else "mixed"}") { it.copy(fullDark = e.full) }
                else if (e.full) set("full-failed") { it.copy(fullDark = false, toast = toast(ToastKind.SWITCH_FAILED)) }
                else SkyLog.w("STAGE mixed reopen failed: ${e.reason}")
            }
            is SkyEvent.LanguageSelected -> {
                prefs?.edit()?.putString("language", e.language.tag)?.apply()
                set("language") { it.copy(language = e.language) }
            }
            is SkyEvent.HoverChanged -> {
                if (e.target == s.hover) return
                val first = e.target is Target.Constellation && !s.pinchHinted
                set("hover") { it.copy(hover = e.target, pinchHinted = it.pinchHinted || first, toast = if (first) toast(ToastKind.PINCH_HINT) else it.toast) }
            }
            is SkyEvent.Pinched -> onPinch(e.pinch)
            SkyEvent.PutBack -> set("put-back") { it.copy(pulled = null, depth = false) }
            is SkyEvent.DepthToggled -> if (s.pulled != null) set("depth") { it.copy(depth = e.on) }
            SkyEvent.BodyCardClosed -> if (s.bodyCard != null) set("body-close") { it.copy(bodyCard = null) }
            SkyEvent.DialStarted -> set("dial-start") { it.stopFast().copy(dialDragging = true, dialFromMs = it.displayTime(clock()), hover = null, popup = null) }
            is SkyEvent.DialMoved -> set("dial-move") { it.copy(live = false, timeBaseMs = e.timeMs, fastSinceWallMs = null) }
            is SkyEvent.DialEnded -> set("dial-end") { it.at(e.timeMs).copy(dialDragging = false) }
            is SkyEvent.TonightPicked -> {
                val c = city(s) ?: return
                var next = s.copy(popup = null)
                if (isDay(s)) {
                    SkyFacts.nextDark(s.displayTime(clock()), c.lat, c.lon)?.let { next = next.at(it) }
                }
                set("guide-start") { next.copy(guide = GuideTarget(e.row.target, clock()), hover = null) }
            }
            SkyEvent.GuideArrived -> set("guide-arrived") { st -> st.copy(guide = st.guide?.copy(arrivedAtMs = clock())) }
            SkyEvent.GuideEnded -> if (s.guide != null && !holdGuide) set("guide-end") { it.copy(guide = null) }
            is SkyEvent.ToastExpired -> if (s.toast?.id == e.id) set("toast-end") { it.copy(toast = null) }
        }
    }

    private fun SkyUiState.stopFast(): SkyUiState = if (fast) frozen() else this

    private fun onPinch(p: Pinch) {
        val s = _state.value
        // a pinch stops fast-forward and the guide, and closes a popup (design M3 clear, S10 exits)
        val base = s.stopFast().copy(guide = null, popup = null)
        when (p) {
            is Pinch.OnConstellation -> {
                if (base.pulled == p.index) {
                    set("pinch-same") { base }
                    return
                }
                set("pull") { base.copy(pulled = p.index, depth = false, hover = null, bodyCard = null) }
                _effects.trySend(SkyEffect.Feedback("pull"))
            }
            is Pinch.OnBody -> {
                if (!base.layer(Layer.BODIES)) return
                set("body-card") { base.copy(bodyCard = p.body, hover = null) }
            }
            Pinch.Empty -> set("empty-pinch") { base.copy(hover = null, bodyCard = null, toast = toast(ToastKind.NO_LINES)) }
        }
    }

    /** "Tonight" rows for the current state (cached for the same minute, city and layers). */
    private var tonightKey: String? = null
    private var tonightCache: TonightUi? = null

    fun tonight(s: SkyUiState = _state.value): TonightUi {
        val cat = catalog ?: return TonightUi(emptyList(), null)
        val c = city(s) ?: return TonightUi(emptyList(), null)
        val t = s.displayTime(clock())
        val key = "${t / 60_000L}|${s.cityId}|${s.layer(Layer.BODIES)}"
        if (key == tonightKey) tonightCache?.let { return it }
        val zone = zone(s)
        val r = SkyFacts.tonight(cat, t, c.lat, c.lon, s.layer(Layer.BODIES)) { Instant.ofEpochMilli(it).atZone(zone).monthValue }
        val ui =
            TonightUi(
                r.rows.map { row ->
                    val target =
                        when (val pk = row.pick) {
                            is Pick.OfBody -> Target.OfBody(pk.body)
                            is Pick.OfConstellation -> Target.Constellation(pk.index)
                        }
                    TonightRow(target, row.horizontal.azDeg, row.horizontal.altDeg)
                },
                r.afterDarkMs,
            )
        tonightKey = key
        tonightCache = ui
        return ui
    }

    fun constellation(index: Int?): Constellation? = index?.let { catalog?.constellations?.getOrNull(it) }

    fun bodyOf(t: Target?): Body? = (t as? Target.OfBody)?.body

    /** Debug: jump straight to a named state (QA screenshots); "clear" drops the override. */
    fun debugState(name: String?) = set("debug-state") { it.copy(debugState = name) }

    fun debugCalibrate(northYawDeg: Float, head: io.embodit.skydome.domain.Vec3) =
        set("debug-calibrate") { it.copy(calibrating = false, everCalibrated = true, demoHeading = false, headingStale = false, northYaw = Math.toRadians(northYawDeg.toDouble()).toFloat(), domeCenter = head) }

    fun debugPatch(why: String, f: (SkyUiState) -> SkyUiState) = set(why, f)

    private const val TOAST_MS = 1500L
    private const val GUIDE_TIMEOUT_MS = 15_000L
    const val DAY_MS = 86_400_000L
}
