package io.embodit.skydome.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.skydome.platform.SkyLog
import kotlin.math.abs

/**
 * The controllers' thumbstick X (factory default interaction, taste profile D1: the stick turns the 3D object
 * about its vertical axis). Read from the provider's action listener (SDK doc "Obtain controller input
 * actions"; same use as Life-size Creatures v0.1.1): the callback runs on the data source thread and only
 * stores the value, the scene reads it once per frame. Every start has its stop (scene attach / detach,
 * pause / resume).
 */
class ControllerStick {
    private var provider: ControllerTrackingProvider? = null

    @Volatile private var stickX = 0f
    @Volatile private var stickAtMs = 0L
    @Volatile var actionCount = 0L
        private set

    // the stick pushed further of the two controllers
    private val actions =
        ControllerTrackingProvider.ControllerActionListener { a ->
            val l = a.left.thumbstickValue.x
            val r = a.right.thumbstickValue.x
            stickX = if (abs(r) >= abs(l)) r else l
            stickAtMs = SystemClock.elapsedRealtime()
            actionCount++
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(actions) }.onFailure { SkyLog.w("TRACK controller actions: $it") }
        val result = runCatching { p.start() }
        SkyLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(actions) }
            runCatching { p.stop() }
        }
        provider = null
        stickX = 0f
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actionCount x=${"%.2f".format(stickX)}"

    /** Thumbstick X in [-1, 1] (right is +), 0 when no controller reports or the last report is stale. */
    fun x(now: Long): Float {
        val p = provider ?: return 0f
        // polling the pose keeps the provider's state current (Life-size Creatures reads it every frame too)
        runCatching { p.latestData }
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return 0f
        if (now - stickAtMs > STALE_MS) return 0f
        return stickX.coerceIn(-1f, 1f)
    }

    private companion object {
        const val STALE_MS = 1_000L
    }
}
