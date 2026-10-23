package io.embodit.skydome.debug

import android.content.Context
import io.embodit.skydome.domain.Body
import io.embodit.skydome.platform.SkyLog
import io.embodit.skydome.ui.sky.LanguagePref
import io.embodit.skydome.ui.sky.Layer
import io.embodit.skydome.ui.sky.Pinch
import io.embodit.skydome.ui.sky.Popup
import io.embodit.skydome.ui.sky.SkyController
import io.embodit.skydome.ui.sky.SkyEvent
import io.embodit.skydome.ui.sky.Target
import io.embodit.skydome.ui.sky.Toast
import io.embodit.skydome.ui.sky.ToastKind
import io.embodit.skydome.ui.sky.debug.Probe
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * Test hooks of the debug build (design `verification.debugHooks`). Every command answers with one log line
 * `CMD <name> -> <result>` (tag `SkyDome`); the state line `STATE S02+M1 ...` follows every change.
 *
 *   setTime 2026-10-20T21:30          displayed time, local to the city (or with an offset: ...+08:00)
 *   setCity sydney                     city id (tools/make_data.py CITIES)
 *   calibrate 180                      north at Stage yaw 180° (0 = ahead at start: "calibrate 180" faces south)
 *   skip | recal | recenter            skip the heading (M1) / back to S01 / heading stale (M7)
 *   pull Ori | putback | depth on|off | turn 60
 *   stick 1 1500                       hold the thumbstick X at 1 (right) for 1.5 s: turns the pulled model
 *   body jupiter | bodyclose           body card (S06)
 *   aim <az> <alt> | aim off | look <Ori|jupiter>   virtual line of sight for hover and pinch
 *   pinch | emptypinch                 a pinch at the current hover / an empty-sky pinch (S12)
 *   fast on|off | now | tonightjump    M3 / back to now / "到今晚"
 *   dial +3 [hold] | dialrelease       drag the 24 h dial (S07, readout visible while held)
 *   popup time|tonight|settings|close  S08 / S09 / S11
 *   tonight 0 | guideend | guidehold on   pick a row of "Tonight" (S10) / drop the guide / keep it (no timeout)
 *   layer lines|bodies|compass on|off  M5
 *   dark on|off | failStage 1          M6 / the next switch fails (S13)
 *   lang system|zh|en
 *   toast pinch|nolines|failed|now|demo|none   a hint that stays until cleared
 *   state S04 | state clear            name override for screenshots
 *   where Ori | dump | pose | panels
 */
object DebugCommands {
    fun run(context: Context, cmd: String, s: String?, a: String?) {
        val result = runCatching { exec(cmd.lowercase(), s?.trim(), a?.trim()) }.getOrElse { "error: $it" }
        SkyLog.i("CMD $cmd -> $result")
    }

    private fun scene() = Probe.host?.scene()

    private fun on(word: String?): Boolean = word == null || word == "on" || word == "1" || word == "true"

    private fun exec(cmd: String, s: String?, a: String?): String {
        val c = SkyController
        val args = listOfNotNull(s, a).joinToString(" ").split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        when (cmd) {
            "settime" -> {
                val text = args.firstOrNull() ?: return "need time"
                val ms =
                    runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
                        ?: LocalDateTime.parse(text).atZone(c.zone()).toInstant().toEpochMilli()
                c.setTime(ms)
                return "time=${c.zoned(ms)}"
            }
            "setcity" -> {
                val id = args.firstOrNull() ?: return "need city"
                c.catalog?.city(id) ?: return "unknown city $id"
                c.onEvent(SkyEvent.CitySelected(id))
                return "city=$id"
            }
            "calibrate" -> {
                val yaw = args.firstOrNull()?.toFloat() ?: 0f
                val head = scene()?.headPosition() ?: return "no scene"
                c.debugCalibrate(yaw, head)
                return "north yaw=$yaw"
            }
            "skip" -> {
                val sc = scene() ?: return "no scene"
                c.onEvent(SkyEvent.CalibrateSkipped(sc.calibrationYaw(), sc.headPosition()))
                return "skipped"
            }
            "recal" -> c.onEvent(SkyEvent.Recalibrate)
            "recenter" -> c.onEvent(SkyEvent.Recentered)
            "pull" -> {
                val k = c.catalog?.constellation(args.firstOrNull() ?: "") ?: return "unknown constellation"
                c.onEvent(SkyEvent.Pinched(Pinch.OnConstellation(k.index)))
                return "pulled ${k.abbr}"
            }
            "putback" -> c.onEvent(SkyEvent.PutBack)
            "depth" -> c.onEvent(SkyEvent.DepthToggled(on(args.firstOrNull())))
            "turn" -> return scene()?.debugTurnModel(args.firstOrNull()?.toFloat() ?: 60f) ?: "no scene"
            "stick" -> return scene()?.debugStick(args.getOrNull(0)?.toFloat() ?: 1f, args.getOrNull(1)?.toLong() ?: 1500L) ?: "no scene"
            "body" -> {
                val b = Body.of(args.firstOrNull() ?: "") ?: return "unknown body"
                c.onEvent(SkyEvent.Pinched(Pinch.OnBody(b)))
                return "body ${b.id}"
            }
            "bodyclose" -> c.onEvent(SkyEvent.BodyCardClosed)
            "aim" -> {
                val sc = scene() ?: return "no scene"
                if (args.firstOrNull() == "off") {
                    sc.debugGaze = null
                    return "gaze=head"
                }
                return sc.debugAim(args.getOrNull(0)?.toDouble() ?: 180.0, args.getOrNull(1)?.toDouble() ?: 30.0)
            }
            "look" -> {
                val sc = scene() ?: return "no scene"
                val where = sc.debugWhere(args.firstOrNull() ?: "")
                val alt = Regex("alt=(-?[0-9.]+)").find(where)?.groupValues?.get(1)?.toDouble() ?: return where
                val az = Regex("az=(-?[0-9.]+)").find(where)?.groupValues?.get(1)?.toDouble() ?: return where
                sc.debugAim(az, alt)
                return where
            }
            "pinch" -> {
                val h = c.state.value.hover ?: return "nothing lit"
                c.onEvent(
                    SkyEvent.Pinched(
                        when (h) {
                            is Target.Constellation -> Pinch.OnConstellation(h.index)
                            is Target.OfBody -> Pinch.OnBody(h.body)
                        }
                    )
                )
            }
            "emptypinch" -> c.onEvent(SkyEvent.Pinched(Pinch.Empty))
            "fast" -> if (on(args.firstOrNull()) != c.state.value.fast) c.onEvent(SkyEvent.FastToggled)
            "now" -> c.onEvent(SkyEvent.BackToNow)
            "tonightjump" -> c.onEvent(SkyEvent.ToTonight)
            "dial" -> {
                val sc = scene() ?: return "no scene"
                val hours = args.firstOrNull()?.toDouble() ?: 3.0
                val from = c.state.value.displayTime(c.clock())
                return sc.debugDial(from + (hours * 3_600_000).toLong(), 1500L, args.getOrNull(1) == "hold")
            }
            "dialrelease" -> return scene()?.debugDialRelease() ?: "no scene"
            "popup" ->
                when (args.firstOrNull()) {
                    "time" -> if (c.state.value.popup != Popup.TIME_PLACE) c.onEvent(SkyEvent.TimeTapped)
                    "tonight" -> if (c.state.value.popup != Popup.TONIGHT) c.onEvent(SkyEvent.TonightTapped)
                    "settings" -> if (c.state.value.popup != Popup.SETTINGS) c.onEvent(SkyEvent.SettingsTapped)
                    else -> c.onEvent(SkyEvent.PopupDismissed)
                }
            "tonight" -> {
                val rows = c.tonight().rows
                val row = rows.getOrNull(args.firstOrNull()?.toInt() ?: 0) ?: return "no row (${rows.size})"
                c.onEvent(SkyEvent.TonightPicked(row))
                return "guide $row"
            }
            "guideend" -> {
                c.holdGuide = false
                c.onEvent(SkyEvent.GuideEnded)
            }
            "guidehold" -> {
                c.holdGuide = on(args.firstOrNull())
                return "holdGuide=${c.holdGuide}"
            }
            "layer" -> {
                val l = Layer.entries.firstOrNull { it.tag == args.firstOrNull() } ?: return "unknown layer"
                c.onEvent(SkyEvent.LayerToggled(l, on(args.getOrNull(1))))
            }
            "dark" -> c.onEvent(SkyEvent.FullDarkToggled(on(args.firstOrNull())))
            "failstage" -> {
                c.failNextStage = on(args.firstOrNull())
                return "failNextStage=${c.failNextStage}"
            }
            "lang" -> c.onEvent(SkyEvent.LanguageSelected(LanguagePref.entries.firstOrNull { it.tag == args.firstOrNull() } ?: LanguagePref.SYSTEM))
            "toast" -> {
                if (args.firstOrNull() == "none") {
                    c.debugPatch("debug-toast") { it.copy(toast = null) }
                    return "toast cleared"
                }
                val k =
                    when (args.firstOrNull()) {
                        "pinch" -> ToastKind.PINCH_HINT
                        "nolines" -> ToastKind.NO_LINES
                        "failed" -> ToastKind.SWITCH_FAILED
                        "demo" -> ToastKind.DEMO_HEADING
                        else -> ToastKind.BACK_TO_NOW
                    }
                c.debugPatch("debug-toast") { it.copy(toast = Toast(k, System.nanoTime())) }
            }
            "state" -> {
                val name = args.firstOrNull()
                c.debugState(if (name == null || name == "clear") null else name)
            }
            "where" -> return scene()?.debugWhere(args.firstOrNull() ?: "") ?: "no scene"
            "pose" -> return scene()?.poseLine() ?: "no scene"
            "panels" -> {
                scene()?.panelLines()?.forEach { SkyLog.i(it) }
                return "panels dumped"
            }
            "dump" -> {
                val st = c.state.value
                SkyLog.i("DUMP state=${c.designState()} $st")
                scene()?.let { SkyLog.i(it.poseLine()) }
                return "dumped"
            }
            else -> return "unknown command"
        }
        return "ok ${c.designState()}"
    }
}
