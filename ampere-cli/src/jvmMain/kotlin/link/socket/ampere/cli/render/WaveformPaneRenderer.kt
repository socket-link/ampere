package link.socket.ampere.cli.render

import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.choreography.AgentLayoutOrientation
import link.socket.phosphor.bridge.CognitiveEvent
import link.socket.phosphor.emitter.EmitterManager
import link.socket.phosphor.field.FlowLayer
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.render.CameraOrbit
import link.socket.phosphor.render.ScreenProjector
import link.socket.phosphor.render.AsciiCell
import link.socket.phosphor.palette.AsciiLuminancePalette
import link.socket.phosphor.palette.CognitiveColorRamp
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.render.CognitiveWaveform
import link.socket.phosphor.render.PhaseBlender
import link.socket.phosphor.render.SurfaceLighting
import link.socket.phosphor.render.WaveformRasterizer
import link.socket.ampere.cli.layout.PaneRenderer

/**
 * Renders the 3D cognitive waveform as a pane within the hybrid dashboard.
 *
 * Replaces the flat spatial map with a living 3D heightmap surface rendered
 * in ASCII, where agent activity creates peaks and ridges, cognitive phases
 * shift the character palette and color, and emitter effects fire transient
 * visual perturbations.
 *
 * The pane orchestrates the full pipeline each frame:
 *   camera orbit → waveform update → emitter update → rasterize → ANSI output
 */
class WaveformPaneRenderer(
    private val agentLayer: AgentLayer,
    private val emitterManager: EmitterManager,
    private val amperePhosphorBridge: AmperePhosphorBridge
) : PaneRenderer {

    private var cameraOrbit = CameraOrbit(
        radius = 15f,
        height = 8f,
        orbitSpeed = 0.08f,
        wobbleAmplitude = 0.3f,
        wobbleFrequency = 0.2f
    )

    private val lighting = SurfaceLighting()
    private val phaseBlender = PhaseBlender(influenceRadius = 8f)

    // Lazily constructed per-size to avoid rebuilding when pane dimensions are stable.
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var waveform: CognitiveWaveform? = null
    private var rasterizer: WaveformRasterizer? = null

    // Animation time tracking
    private var elapsedTime = 0f
    private var deltaSeconds = 0.033f // ~30fps default, updated externally

    // Substrate and flow state provided externally via update()
    private var currentSubstrate: SubstrateState? = null
    private var currentFlow: FlowLayer? = null

    /**
     * Update the renderer's animation state before the next render call.
     *
     * @param substrate Current substrate density field
     * @param flow Current flow connections (nullable)
     * @param dt Delta time since last frame in seconds
     */
    fun update(substrate: SubstrateState, flow: FlowLayer?, dt: Float) {
        currentSubstrate = substrate
        currentFlow = flow
        deltaSeconds = dt
        elapsedTime += dt
    }

    /**
     * Fire a cognitive event through the emitter bridge.
     */
    fun onCognitiveEvent(event: CognitiveEvent, agentPosition: Vector3) {
        amperePhosphorBridge.onCognitiveEvent(event, agentPosition)
    }

    override fun render(width: Int, height: Int): List<String> {
        ensureComponents(width, height)

        val wf = waveform ?: return emptyGrid(width, height)
        val rast = rasterizer ?: return emptyGrid(width, height)

        // 1. Advance camera orbit
        val camera = cameraOrbit.update(deltaSeconds)

        // 2. Update waveform heightmap from current state
        val substrate = currentSubstrate ?: SubstrateState.create(width, height, baseDensity = 0.1f)
        wf.update(substrate, agentLayer, currentFlow, deltaSeconds)

        // 3. Update emitter effects
        emitterManager.update(deltaSeconds)

        // 4. Rasterize with phase blending for per-point palette selection
        val cells: Array<Array<AsciiCell>> = if (agentLayer.agentCount > 0) {
            rast.rasterizeBlended(
                waveform = wf,
                camera = camera,
                blender = phaseBlender,
                agents = agentLayer,
                fallbackPalette = AsciiLuminancePalette.STANDARD,
                fallbackRamp = CognitiveColorRamp.NEUTRAL,
                emitterManager = emitterManager
            )
        } else {
            rast.rasterize(
                waveform = wf,
                camera = camera,
                palette = AsciiLuminancePalette.STANDARD,
                colorRamp = CognitiveColorRamp.NEUTRAL,
                emitterManager = emitterManager
            )
        }

        // 5. Convert AsciiCell grid to ANSI-styled strings
        return cellsToLines(cells, width, height)
    }

    /**
     * Ensure waveform and rasterizer are sized correctly for the current pane dimensions.
     */
    private fun ensureComponents(width: Int, height: Int) {
        if (width != cachedWidth || height != cachedHeight) {
            cachedWidth = width
            cachedHeight = height

            // Heightmap resolution: use pane dimensions directly for 1:1 mapping
            // but cap grid resolution to keep frame time under budget.
            val gridWidth = width.coerceAtMost(60)
            val gridDepth = height.coerceAtMost(40)

            waveform = CognitiveWaveform(
                gridWidth = gridWidth,
                gridDepth = gridDepth,
                worldWidth = 20f,
                worldDepth = 15f
            )

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
