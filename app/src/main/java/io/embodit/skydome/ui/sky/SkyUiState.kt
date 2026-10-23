package io.embodit.skydome.ui.sky

import io.embodit.skydome.domain.Body
import io.embodit.skydome.domain.Vec3

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en"),
}

enum class Popup { TIME_PLACE, TONIGHT, SETTINGS }

enum class Layer(val tag: String) {
    LINES("lines"),
    BODIES("bodies"),
    COMPASS("compass"),
}

enum class ToastKind {
    PINCH_HINT,
    NO_LINES,
    SWITCH_FAILED,
    BACK_TO_NOW,
    DEMO_HEADING,
}

data class Toast(val kind: ToastKind, val id: Long)

/** What the sight or a pinch is on. */
sealed class Target {
    data class Constellation(val index: Int) : Target()

    data class OfBody(val body: Body) : Target()
}

/** A "tonight" target the guide arrow leads to. */
data class GuideTarget(val target: Target, val startedAtMs: Long, val arrivedAtMs: Long? = null)

/** One row of "Tonight". */
data class TonightRow(val target: Target, val azDeg: Double, val altDeg: Double)

data class TonightUi(val rows: List<TonightRow>, val afterDarkMs: Long?)

/**
 * Everything the panels and the scene draw. Time: [timeBaseMs] is the displayed moment while not live;
 * while [live] the displayed time is the clock; while [fastSinceWallMs] is set it runs 600x from
 * [timeBaseMs] (1 s = 10 min).
 */
data class SkyUiState(
    val loaded: Boolean = false,
    val calibrating: Boolean = true,
    val calibrateChoice: Int = 0,
    val everCalibrated: Boolean = false,
    val demoHeading: Boolean = false,
    val headingStale: Boolean = false,
    /** Stage yaw of true north (or of the demo north). */
    val northYaw: Float = 0f,
    /** Centre of the dome: the head at calibration (Stage metres). */
    val domeCenter: Vec3? = null,
    val live: Boolean = true,
    val timeBaseMs: Long = 0L,
    val fastSinceWallMs: Long? = null,
    val cityId: String = "beijing",
    val cityFallback: Boolean = false,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val layers: Set<Layer> = Layer.entries.toSet(),
    val fullDark: Boolean = false,
    val popup: Popup? = null,
    val hover: Target? = null,
    val pulled: Int? = null,
    val depth: Boolean = false,
    val bodyCard: Body? = null,
    val dialDragging: Boolean = false,
    val dialFromMs: Long = 0L,
    val guide: GuideTarget? = null,
    val toast: Toast? = null,
    val pinchHinted: Boolean = false,
    /** Wall clock of the last state tick (drives the hand-bar clock while live / fast). */
    val tickMs: Long = 0L,
    /** Debug `state` override for QA screenshots; null = derived. */
    val debugState: String? = null,
) {
    val fast: Boolean
        get() = fastSinceWallMs != null

    fun displayTime(wallMs: Long): Long =
        when {
            fastSinceWallMs != null -> timeBaseMs + (wallMs - fastSinceWallMs) * FAST_FACTOR
            live -> wallMs
            else -> timeBaseMs
        }

    fun layer(l: Layer) = l in layers

    /** The base state of the design (S00 - S13). */
    val baseState: String
        get() =
            when {
                !loaded -> "S00"
                calibrating -> "S01"
                toast?.kind == ToastKind.SWITCH_FAILED -> "S13"
                toast?.kind == ToastKind.NO_LINES -> "S12"
                popup == Popup.SETTINGS -> "S11"
                popup == Popup.TONIGHT -> "S09"
                popup == Popup.TIME_PLACE -> "S08"
                dialDragging -> "S07"
                bodyCard != null -> "S06"
                guide != null -> "S10"
                hover != null -> "S03"
                pulled != null && depth -> "S05"
                pulled != null -> "S04"
                else -> "S02"
            }

    companion object {
        const val FAST_FACTOR = 600L
    }
}

/** Kinds of things a pinch lands on (the scene resolves the hit priority). */
sealed class Pinch {
    data class OnConstellation(val index: Int) : Pinch()

    data class OnBody(val body: Body) : Pinch()

    object Empty : Pinch()
}

sealed class SkyEvent {
    object SceneReady : SkyEvent()

    data class CalibrateChoice(val direction: Int) : SkyEvent()

    /** "就是这边": the head yaw the scene measured (median of the last 0.5 s) and the head position. */
    data class CalibrateConfirmed(val headYaw: Float, val head: Vec3) : SkyEvent()

    data class CalibrateSkipped(val headYaw: Float, val head: Vec3) : SkyEvent()

    object Recalibrate : SkyEvent()

    object TimeTapped : SkyEvent()

    object TonightTapped : SkyEvent()

    object SettingsTapped : SkyEvent()

    object PopupDismissed : SkyEvent()

    object FastToggled : SkyEvent()

    object BackToNow : SkyEvent()

    object ToTonight : SkyEvent()

    data class DateStep(val days: Int) : SkyEvent()

    object Today : SkyEvent()

    data class CitySelected(val id: String) : SkyEvent()

    data class LayerToggled(val layer: Layer, val on: Boolean) : SkyEvent()

    data class FullDarkToggled(val on: Boolean) : SkyEvent()

    data class StageSwitched(val full: Boolean, val ok: Boolean, val reason: String?) : SkyEvent()

    data class LanguageSelected(val language: LanguagePref) : SkyEvent()

    data class HoverChanged(val target: Target?) : SkyEvent()

    data class Pinched(val pinch: Pinch) : SkyEvent()

    object PutBack : SkyEvent()

    data class DepthToggled(val on: Boolean) : SkyEvent()

    object BodyCardClosed : SkyEvent()

    object DialStarted : SkyEvent()

    data class DialMoved(val timeMs: Long) : SkyEvent()

    data class DialEnded(val timeMs: Long) : SkyEvent()

    data class TonightPicked(val row: TonightRow) : SkyEvent()

    object GuideArrived : SkyEvent()

    object GuideEnded : SkyEvent()

    data class ToastExpired(val id: Long) : SkyEvent()

    object Recentered : SkyEvent()

    object Tick : SkyEvent()
}

sealed class SkyEffect {
    data class Feedback(val kind: String) : SkyEffect()
}
