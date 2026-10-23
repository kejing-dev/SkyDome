package io.embodit.skydome.ui.sky.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.skydome.R
import io.embodit.skydome.domain.Body
import io.embodit.skydome.platform.SkyLog
import io.embodit.skydome.ui.sky.SkyController
import io.embodit.skydome.ui.sky.SkyEvent
import io.embodit.skydome.ui.sky.SkyUiState
import io.embodit.skydome.ui.sky.Target
import io.embodit.skydome.ui.sky.debug.LocalProbePanel
import io.embodit.skydome.ui.sky.debug.Probe
import io.embodit.skydome.ui.sky.scene.PanelIds
import io.embodit.skydome.ui.sky.scene.SkyScene

/**
 * The Stage content: one full-size SpatialView holding the sky and the panel pool. Every attachment panel
 * is declared once with a fixed size and shown or not ([SkyScene] parents and places them). Tap and drag
 * recognizers live in separate pointerInput blocks (Spatial Ruler).
 *
 * Window model `stage_with_hand_bar`: the hand bar (or the calibrate panel) is the one resident panel, the
 * popups open above it, the cards ride on the pulled-down model, the tags stand in the sky.
 */
@Composable
internal fun SkyStage(state: SkyUiState, scene: SkyScene, onEvent: (SkyEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value -> scene.onSpatialDrag("move", null, value) }
                },
        attachments = {
            AttachmentPanel(PanelIds.BAR) { PoolPanel(PanelIds.BAR, scene) { inView -> HandBar(if (inView) state else null, onEvent) } }
            AttachmentPanel(PanelIds.CAL) {
                PoolPanel(PanelIds.CAL, scene) { inView ->
                    CalibratePanel(
                        state = if (inView) state else null,
                        onConfirm = { onEvent(SkyEvent.CalibrateConfirmed(scene.calibrationYaw(), scene.headPosition())) },
                        onSkip = { onEvent(SkyEvent.CalibrateSkipped(scene.calibrationYaw(), scene.headPosition())) },
                        onEvent = onEvent,
                    )
                }
            }
            AttachmentPanel(PanelIds.TIME) { PoolPanel(PanelIds.TIME, scene) { inView -> TimePlacePanel(if (inView) state else null, onEvent) } }
            AttachmentPanel(PanelIds.TONIGHT) { PoolPanel(PanelIds.TONIGHT, scene) { inView -> TonightPanel(if (inView) state else null, onEvent) } }
            AttachmentPanel(PanelIds.SETTINGS) { PoolPanel(PanelIds.SETTINGS, scene) { inView -> SettingsPanel(if (inView) state else null, onEvent) } }
            AttachmentPanel(PanelIds.CARD) { PoolPanel(PanelIds.CARD, scene) { inView -> ConstellationCard(if (inView) state else null, onEvent) } }
            AttachmentPanel(PanelIds.BODY) { PoolPanel(PanelIds.BODY, scene) { inView -> BodyCard(if (inView) state else null) } }
            AttachmentPanel(PanelIds.HOVER) {
                PoolPanel(PanelIds.HOVER, scene) { inView ->
                    val idx = (state.hover as? Target.Constellation)?.index ?: (state.guide?.target as? Target.Constellation)?.index
                    val zh = LocalSkyResources.current.zh()
                    SkyTag(if (inView) SkyController.constellation(idx)?.name(zh) else null, PanelSizes.TAG_W, lit = true)
                }
            }
            AttachmentPanel(PanelIds.READOUT) { PoolPanel(PanelIds.READOUT, scene) { inView -> ReadoutPanel(if (inView) scene.dialReadout["delta"] else null, scene.dialReadout["time"], state) } }
            AttachmentPanel(PanelIds.TOAST) { PoolPanel(PanelIds.TOAST, scene) { inView -> ToastPanel(if (inView) state.toast?.kind else null) } }
            AttachmentPanel(PanelIds.EYE) { PoolPanel(PanelIds.EYE, scene) { inView -> SkyTag(if (inView) rs(R.string.you_are_here) else null, PanelSizes.READOUT_W, lit = true, sky = false) } }
            for (i in 0 until PanelIds.BODY_TAGS) {
                AttachmentPanel(PanelIds.bodyTag(i)) {
                    PoolPanel(PanelIds.bodyTag(i), scene) { inView ->
                        val b: Body = PanelIds.TAG_BODIES[i]
                        val lit = (state.hover as? Target.OfBody)?.body == b || state.bodyCard == b
                        SkyTag(if (inView) LocalSkyResources.current.bodyName(b) else null, PanelSizes.SMALL_TAG_W, lit = lit)
                    }
                }
            }
            for (i in 0 until PanelIds.COMPASS_TAGS) {
                AttachmentPanel(PanelIds.compass(i)) {
                    PoolPanel(PanelIds.compass(i), scene) { inView ->
                        val r = LocalSkyResources.current
                        val word =
                            when {
                                i == 0 && state.headingStale -> r.getString(R.string.north_stale)
                                i == 0 && state.demoHeading -> r.getString(R.string.north_demo)
                                else -> r.getStringArray(R.array.compass4)[i]
                            }
                        SkyTag(if (inView) word else null, PanelSizes.SMALL_TAG_W, alert = i == 0 && (state.demoHeading || state.headingStale))
                    }
                }
            }
            for (i in 0 until PanelIds.TICKS) {
                AttachmentPanel(PanelIds.tick(i)) {
                    PoolPanel(PanelIds.tick(i), scene) { inView ->
                        val r = LocalSkyResources.current
                        SkyTag(if (inView) r.getString(R.string.ly, listOf("10", "100", "1,000")[i]) else null, PanelSizes.READOUT_W, sky = false)
                    }
                }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel: [content] gets `inView` (the panel has been moved into view; a pooled panel draws
 * only then, Spatial Ruler finding 3). Debug builds log every press as `PTR panel=<id> ev=`.
 */
@Composable
private fun PoolPanel(id: String, scene: SkyScene, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val logged = Probe.enabled
    val watcher =
        if (logged) {
            Modifier.pointerInput(id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) continue
                        val p = event.changes.firstOrNull()?.position
                        SkyLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                    }
                }
            }
        } else Modifier
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
