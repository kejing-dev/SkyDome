package io.embodit.skydome.ui.sky

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.containers.StageStyle
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.OpenStageResult
import io.embodit.skydome.platform.SkyLog
import io.embodit.skydome.ui.sky.components.LocalSkyResources
import io.embodit.skydome.ui.sky.components.SkyStage
import io.embodit.skydome.ui.sky.components.localizedResources
import io.embodit.skydome.ui.sky.debug.Probe
import io.embodit.skydome.ui.sky.scene.SkyFrameSystem
import io.embodit.skydome.ui.sky.scene.SkyScene

/** Stage ids: the default Mixed stage (AndroidManifest) and the Full one for "全黑" (design container.fullSwitch). */
const val MIXED_STAGE_ID = "SkyDomeStage"
const val FULL_STAGE_ID = "SkyDomeFull"

/** The two Stages can overlap while one closes and the other opens: the per-frame system is counted. */
private var systemUsers = 0

/**
 * The one screen, in whichever Stage is open. It wires the process-wide [SkyController] (state), the
 * [SkyScene] (entities, per-frame work, taps and drags) and [SkyStage] (the panels), and owns nothing: the
 * state survives the Stage being re-opened with the other style.
 */
@Composable
fun SkyScreen(stageId: String) {
    val context = LocalContext.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        SkyController.start(context)
        true
    }
    val state by SkyController.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { SkyScene(SkyController::onEvent, stageId) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        // the one per-frame system; every start has its stop in onDispose
        if (systemUsers++ == 0) registerSystem<SkyFrameSystem>()
        Probe.host = object : Probe.Host { override fun scene(): SkyScene = io.embodit.skydome.ui.sky.scene.ActiveScenes.current() ?: scene }
        // in the background head tracking stops and fast-forward ends (design container.lifecycle)
        val observer =
            LifecycleEventObserver { _, event ->
                SkyLog.i("LIFECYCLE stage=$stageId event=$event current=${SkyController.currentStageId}")
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        scene.pause()
                        if (SkyController.state.value.fast) SkyController.onEvent(SkyEvent.FastToggled)
                    }
                    Lifecycle.Event.ON_START -> scene.resume()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        SkyLog.i("STAGE enter id=$stageId")
        onDispose {
            lifecycle.removeObserver(observer)
            if (io.embodit.skydome.ui.sky.scene.ActiveScenes.all.size <= 1) Probe.host = null
            if (--systemUsers == 0) unregisterSystem<SkyFrameSystem>()
            scene.detach()
            SkyLog.i("STAGE disposed id=$stageId")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(scene) {
        SkyController.effects.collect { effect ->
            when (effect) {
                is SkyEffect.Feedback -> runCatching { audio.playSystem(if (effect.kind == "pull") SpatialSoundEffect.StateSelected else SpatialSoundEffect.OpClick) }
            }
        }
    }

    // a Stage switch is made by the screen of the Stage that is open (its navigator is the one allowed to)
    LaunchedEffect(stageId) {
        SkyController.stageRequest.collect { full ->
            if (full == null || SkyController.currentStageId != stageId) return@collect
            SkyController.stageRequest.value = null
            val id = if (full) FULL_STAGE_ID else MIXED_STAGE_ID
            val style = if (full) StageStyle.Full else StageStyle.Mixed
            val result = runCatching { navigator.openStage(id, style) }
            SkyLog.i("STAGE open id=$id style=$style from=$stageId result=${result.getOrNull() ?: result.exceptionOrNull()}")
            val ok = result.getOrNull() is OpenStageResult.Allowed
            SkyController.onEvent(SkyEvent.StageSwitched(full, ok, result.getOrNull()?.toString() ?: result.exceptionOrNull()?.toString()))
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalSkyResources provides resources) {
        SkyStage(state = state, scene = scene, onEvent = SkyController::onEvent)
    }
}
