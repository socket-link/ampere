package link.socket.ampere.cli.render

import link.socket.ampere.cli.layout.PaneRenderer
import link.socket.ampere.cli.watch.presentation.ProviderCallTelemetrySummary
import link.socket.phosphor.bridge.CognitiveEvent
import link.socket.phosphor.emitter.EmitterManager
import link.socket.phosphor.field.FlowLayer
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.palette.AsciiLuminancePalette
import link.socket.phosphor.palette.CognitiveColorRamp
import link.socket.phosphor.render.AsciiCell
import link.socket.phosphor.render.PhaseBlender
import link.socket.phosphor.render.ScreenProjector
import link.socket.phosphor.render.SurfaceLighting
import link.socket.phosphor.render.WaveformRasterizer
import link.socket.phosphor.choreography.AgentLayer

/**
 * Renders the 3D cognitive waveform as a pane within the hybrid dashboard.
 *
 * The pane is driven by [CognitiveSceneRuntimeAdapter], so camera orbit, waveform,
 * flow, and emitter updates are advanced via a single runtime update per frame.
 */
class WaveformPaneRenderer(
    private val agentLayer: AgentLayer,
    private val runtimeAdapter: CognitiveSceneRuntimeAdapter = CognitiveSceneRuntimeAdapter()
) : PaneRenderer {
    private val lighting = SurfaceLighting()
    private val phaseBlender = PhaseBlender(influenceRadius = 8f)

    // Lazily constructed per-size to avoid rebuilding when pane dimensions are stable.
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var rasterizer: WaveformRasterizer? = null

    // Animation time tracking
    private var deltaSeconds = 0.033f // ~30fps default, updated externally

    // Source state from watch bridge (used to synchronize runtime flow)
    private var currentFlow: FlowLayer? = null

    // Runtime-backed emitter bridge. Rebuilt if runtime instance changes.
    private var bridgeEmitterManager: EmitterManager? = null
    private var amperePhosphorBridge: AmperePhosphorBridge? = null

    // Events can arrive before runtime init (first render), so queue them.
    private val queuedCognitiveEvents = mutableListOf<Pair<CognitiveEvent, Vector3>>()
    private val queuedProviderTelemetry = mutableListOf<Pair<ProviderCallTelemetrySummary, Vector3>>()

    /**
     * Update the renderer's animation inputs before the next render call.
     *
     * @param substrate Unused by the runtime adapter; kept for call-site compatibility.
     * @param flow Current flow connections (nullable)
     * @param dt Delta time since last frame in seconds
     */
    fun update(substrate: SubstrateState, flow: FlowLayer?, dt: Float) {
        currentFlow = flow
        deltaSeconds = dt
    }

    /**
     * Fire a cognitive event through the emitter bridge.
     *
     * If the runtime is not initialized yet, the event is buffered and emitted on the next render.
     */
    fun onCognitiveEvent(event: CognitiveEvent, agentPosition: Vector3) {
        val bridge = amperePhosphorBridge
        if (bridge == null) {
            queuedCognitiveEvents += event to agentPosition
        } else {
            bridge.onCognitiveEvent(event, agentPosition)
        }
    }

    /**
     * Emit provider telemetry metadata through Ampere's phase-aware bridge.
     *
     * If the runtime is not initialized yet, the event is buffered and emitted on the next render.
     */
    fun onProviderCallCompleted(event: ProviderCallTelemetrySummary, agentPosition: Vector3) {
        val bridge = amperePhosphorBridge
        if (bridge == null) {
            queuedProviderTelemetry += event to agentPosition
        } else {
            bridge.onProviderCallCompleted(event, agentPosition)
        }
    }

    override fun render(width: Int, height: Int): List<String> {
        ensureRasterizer(width, height)
        runtimeAdapter.update(
            width = width,
            height = height,
            deltaSeconds = deltaSeconds,
            sourceAgents = agentLayer,
            sourceFlow = currentFlow
        )

        ensureBridge()
        flushQueuedEvents()

        val waveform = runtimeAdapter.waveform ?: return emptyGrid(width, height)
        val camera = runtimeAdapter.currentCamera() ?: return emptyGrid(width, height)
        val runtimeAgents = runtimeAdapter.agents ?: return emptyGrid(width, height)
        val emitters = runtimeAdapter.emitterManager ?: return emptyGrid(width, height)
        val rast = rasterizer ?: return emptyGrid(width, height)

        val cells: Array<Array<AsciiCell>> = if (runtimeAgents.agentCount > 0) {
            rast.rasterizeBlended(
                waveform = waveform,
                camera = camera,
                blender = phaseBlender,
                agents = runtimeAgents,
                fallbackPalette = AsciiLuminancePalette.STANDARD,
                fallbackRamp = CognitiveColorRamp.NEUTRAL,
                emitterManager = emitters
            )
        } else {
            rast.rasterize(
                waveform = waveform,
                camera = camera,
                palette = AsciiLuminancePalette.STANDARD,
                colorRamp = CognitiveColorRamp.NEUTRAL,
                emitterManager = emitters
            )
        }

        return cellsToLines(cells, width, height)
    }

    private fun ensureBridge() {
        val emitters = runtimeAdapter.emitterManager ?: return
        if (bridgeEmitterManager !== emitters) {
            bridgeEmitterManager = emitters
            amperePhosphorBridge = AmperePhosphorBridge(emitters)
        }
    }

    private fun flushQueuedEvents() {
        val bridge = amperePhosphorBridge ?: return

        if (queuedCognitiveEvents.isNotEmpty()) {
            for ((event, position) in queuedCognitiveEvents) {
                bridge.onCognitiveEvent(event, position)
            }
            queuedCognitiveEvents.clear()
        }

        if (queuedProviderTelemetry.isNotEmpty()) {
            for ((event, position) in queuedProviderTelemetry) {
                bridge.onProviderCallCompleted(event, position)
            }
            queuedProviderTelemetry.clear()
        }
    }

    private fun ensureRasterizer(width: Int, height: Int) {
        if (width != cachedWidth || height != cachedHeight) {
            cachedWidth = width
            cachedHeight = height

            val projector = ScreenProjector(
                screenWidth = width,
                screenHeight = height
            )

            rasterizer = WaveformRasterizer(
                screenWidth = width,
                screenHeight = height,
                projector = projector,
                lighting = lighting
            )
        }
    }

    private fun emptyGrid(width: Int, height: Int): List<String> {
        val emptyLine = " ".repeat(width)
        return List(height) { emptyLine }
    }

    internal fun activeEmitterCount(): Int = runtimeAdapter.emitterManager?.activeCount ?: 0

    internal fun runtimeFrameIndex(): Long? = runtimeAdapter.snapshot()?.frameIndex

    companion object {
        /**
         * Convert a 2D AsciiCell grid to a list of ANSI-styled strings,
         * one per row. Each line is exactly [width] visible characters.
         */
        fun cellsToLines(
            cells: Array<Array<AsciiCell>>,
            width: Int,
            height: Int
        ): List<String> {
            return List(height) { row ->
                if (row >= cells.size) {
                    " ".repeat(width)
                } else {
                    buildString {
                        val rowCells = cells[row]
                        var lastColor: String? = null
                        for (col in 0 until width) {
                            val cell = if (col < rowCells.size) rowCells[col] else AsciiCell.EMPTY
                            val ansiColor = WaveformCellAdapter.toAnsiColor(cell)

                            if (ansiColor != lastColor) {
                                if (lastColor != null) append("\u001B[0m")
                                if (ansiColor != null) append(ansiColor)
                                lastColor = ansiColor
                            }
                            append(cell.char)
                        }
                        if (lastColor != null) append("\u001B[0m")
                    }
                }
            }
        }
    }
}
