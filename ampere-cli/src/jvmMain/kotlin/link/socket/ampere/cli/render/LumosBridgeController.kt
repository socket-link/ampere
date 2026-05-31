package link.socket.ampere.cli.render

import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.phosphor.AmperePhosphorBridge
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.runtime.CognitiveSceneRuntime

/**
 * Owns the AMPR-169 [AmperePhosphorBridge] for the TUI's lifetime.
 *
 * The bridge needs a [CognitiveSceneRuntime] instance, which the dashboard
 * builds lazily on the first render (dimensions are required). This controller
 * lets the dashboard be constructed before that runtime exists: each [tick]
 * polls [sceneProvider] until a runtime is available, then starts the bridge
 * and forwards subsequent ticks to [AmperePhosphorBridge.onFrameTick].
 *
 * [stop] is idempotent so it is safe to call from a finally block even if the
 * bridge never started (e.g. dashboard was disposed before its first render).
 */
class LumosBridgeController(
    private val bus: EventSerialBus,
    private val sceneProvider: () -> CognitiveSceneRuntime?,
    private val voxelResolution: Int = DEFAULT_VOXEL_RESOLUTION,
    private val bridgeFactory: (EventSerialBus, CognitiveSceneRuntime, VoxelFrameBuilder) -> AmperePhosphorBridge =
        { b, r, vfb -> AmperePhosphorBridge(bus = b, runtime = r, voxelFrameBuilder = vfb) },
) {
    private var bridge: AmperePhosphorBridge? = null
    private var voxelFrameBuilder: VoxelFrameBuilder? = null

    val isStarted: Boolean
        get() = bridge != null

    suspend fun tick() {
        val active = bridge ?: tryStart() ?: return
        active.onFrameTick()
    }

    fun stop() {
        bridge?.stop()
        bridge = null
        voxelFrameBuilder = null
    }

    private fun tryStart(): AmperePhosphorBridge? {
        val runtime = sceneProvider() ?: return null
        val vfb = VoxelFrameBuilder(initialResolution = voxelResolution)
        val started = bridgeFactory(bus, runtime, vfb).also { it.start() }
        voxelFrameBuilder = vfb
        bridge = started
        return started
    }

    companion object {
        const val DEFAULT_VOXEL_RESOLUTION: Int = 16
    }
}
