package link.socket.ampere.cli.hybrid

import com.github.ajalt.mordant.terminal.Terminal
import link.socket.ampere.animation.emitter.CognitiveEmitterBridge
import link.socket.ampere.animation.emitter.EmitterManager
import link.socket.ampere.animation.particle.ParticleSystem
import link.socket.ampere.cli.animation.render.AmperePalette
import link.socket.ampere.cli.animation.render.CompositeRenderer
import link.socket.ampere.animation.substrate.SubstrateAnimator
import link.socket.ampere.animation.substrate.SubstrateGlyphs
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.cli.layout.AnsiCellParser
import link.socket.ampere.cli.layout.PaneRenderer
import link.socket.ampere.cli.layout.fitToHeight
import link.socket.ampere.cli.layout.fitToWidth
import link.socket.ampere.cli.render.WaveformPaneRenderer
import link.socket.ampere.cli.watch.presentation.WatchViewState
import link.socket.ampere.repl.TerminalFactory
import kotlin.math.roundToInt

/**
 * Configuration for the hybrid dashboard renderer.
 */
data class HybridConfig(
    val enableSubstrate: Boolean = true,
    val enableParticles: Boolean = true,
    val enableWaveform: Boolean = true,
    val substrateOpacity: Float = 0.15f,
    val particleMaxCount: Int = 30,
    val useColor: Boolean = CompositeRenderer.supports256Colors(),
    val useUnicode: Boolean = CompositeRenderer.supportsUnicode(),
    val leftRatio: Float = 0.35f,
    val middleRatio: Float = 0.40f,
    val rightRatio: Float = 0.25f
)

/**
 * Hybrid dashboard renderer that composites animated substrate and particle accents
 * underneath the standard three-column layout.
 *
 * Animation appears ONLY in "accent areas" (divider columns, left/right margins)
 * and never obscures pane content.
 *
 * Render pipeline per frame:
 *   1. Bridge updates animation state from WatchViewState
 *   2. Substrate ambient animation step
 *   3. Particle physics step
 *   4. Clear cell buffer
 *   5. Write substrate glyphs to accent areas at layer 0
 *   6. Parse pane output and write at layer 1
 *   7. Write divider characters at divider columns
 *   8. Write particle glyphs at layer 2 (accent areas only)
 *   9. Write status bar
 *  10. Return differential output
 */
class HybridDashboardRenderer(
    private val terminal: Terminal?,
    private val config: HybridConfig = HybridConfig(),
    private val explicitWidth: Int? = null,
    private val explicitHeight: Int? = null
) {
    private lateinit var buffer: HybridCellBuffer
    private lateinit var substrate: SubstrateState
    private lateinit var substrateAnimator: SubstrateAnimator
    private lateinit var particles: ParticleSystem
    private lateinit var bridge: WatchStateAnimationBridge

    // 3D waveform pipeline
    private lateinit var emitterManager: EmitterManager
    private lateinit var cognitiveEmitterBridge: CognitiveEmitterBridge

    /**
     * The waveform pane renderer for the middle pane. Callers can pass this
     * as the `middlePane` parameter to [render] or [renderToBuffer] to display
     * the 3D cognitive waveform instead of the flat spatial map.
     *
     * Only available after [initialize] has been called and [HybridConfig.enableWaveform] is true.
     */
    var waveformPane: WaveformPaneRenderer? = null
        private set

    private var initialized = false
    private var frameCount = 0L
    private var resized = false

    // Column layout (mirrors ThreeColumnLayout calculations)
    private var totalWidth = 0
    private var totalHeight = 0
    private var contentHeight = 0
    private var leftWidth = 0
    private var middleWidth = 0
    private var rightWidth = 0
    private var divider1Col = 0
    private var divider2Col = 0
    private var accentColumns = setOf<Int>()

    /**
     * Initialize renderer with current terminal dimensions.
     * Must be called before first render.
     */
    fun initialize() {
        // Use TerminalFactory for live dimensions (updated via SIGWINCH) instead of
        // stale terminal.info values which are captured once at Terminal creation time.
        val liveCaps = if (terminal != null) TerminalFactory.getCapabilities() else null
        totalWidth = (explicitWidth ?: liveCaps?.width ?: 80).coerceAtLeast(40)
        totalHeight = (explicitHeight ?: liveCaps?.height ?: 24).coerceAtLeast(10)
        contentHeight = totalHeight - 2

        // Replicate ThreeColumnLayout width calculations
        val availableWidth = totalWidth - 2 // Account for 2 dividers
        leftWidth = (availableWidth * config.leftRatio).toInt()
        rightWidth = (availableWidth * config.rightRatio).toInt()
        middleWidth = availableWidth - leftWidth - rightWidth

        divider1Col = leftWidth
        divider2Col = leftWidth + 1 + middleWidth

        // Accent areas: first column, divider columns, last column
        accentColumns = buildSet {
            add(0)                      // Left margin
            add(divider1Col)            // First divider
            add(divider2Col)            // Second divider
            if (totalWidth > 0) {
                add(totalWidth - 1)     // Right margin
            }
        }

        buffer = HybridCellBuffer(totalWidth, totalHeight)

        substrate = SubstrateState.create(totalWidth, totalHeight, baseDensity = 0.2f)
        substrateAnimator = SubstrateAnimator(
            baseDensity = 0.2f,
            noiseAmplitude = 0.15f,
            timeScale = 0.03f
        )
        particles = ParticleSystem(
            maxParticles = config.particleMaxCount,
            drag = 0.2f,
            lifeDecayRate = 0.8f
        )

        bridge = WatchStateAnimationBridge(
            substrateAnimator = substrateAnimator,
            particles = particles,
            accentColumns = accentColumns,
            height = contentHeight,
            maxParticles = config.particleMaxCount
        )

        // Initialize waveform pipeline
        emitterManager = EmitterManager()
        cognitiveEmitterBridge = CognitiveEmitterBridge(emitterManager)

        if (config.enableWaveform) {
            val wfPane = WaveformPaneRenderer(
                agentLayer = bridge.agentLayer,
                emitterManager = emitterManager,
                cognitiveEmitterBridge = cognitiveEmitterBridge
            )
            waveformPane = wfPane

            // Wire cognitive events from bridge to emitter bridge
            bridge.onCognitiveEvent = { event, position ->
                cognitiveEmitterBridge.onCognitiveEvent(event, position)
            }
        }

        initialized = true
    }

    /**
     * Render one frame of the hybrid dashboard.
     *
     * @param leftPane Left pane renderer (event stream)
     * @param middlePane Middle pane renderer (main interaction)
     * @param rightPane Right pane renderer (agent/memory)
     * @param statusBar Status bar text
     * @param viewState Current watch view state (null-safe)
     * @param deltaSeconds Time since last frame
     * @return ANSI string to write to terminal
     */
    fun render(
        leftPane: PaneRenderer,
        middlePane: PaneRenderer,
        rightPane: PaneRenderer,
        statusBar: String,
        viewState: WatchViewState? = null,
        deltaSeconds: Float = 0.033f
    ): String {
        if (!initialized) initialize()

        // Detect terminal resize (only when using live terminal, not explicit dimensions).
        // Uses TerminalFactory.getCapabilities() which is updated via SIGWINCH signal handler,
        // rather than stale terminal.info values that are captured once at Terminal creation.
        if (explicitWidth == null && terminal != null) {
            val caps = TerminalFactory.getCapabilities()
            val currentWidth = caps.width
            val currentHeight = caps.height
            if (currentWidth != totalWidth || currentHeight != totalHeight) {
                initialize()
                // Force full redraw (not diff) after resize to avoid artifacts
                frameCount = 0
                resized = true
            }
        }

        // 1. Bridge updates animation from watch state
        substrate = bridge.update(viewState, substrate, deltaSeconds)

        // 2. Update waveform pane with current substrate/flow state
        waveformPane?.update(substrate, flow = null, dt = deltaSeconds)

        // 3. (ambient + particle updates handled inside bridge.update)

        // 4. Clear buffer
        buffer.clear()

        // 5. Write substrate glyphs to accent areas only (layer 0)
        if (config.enableSubstrate) {
            renderSubstrateAccents()
        }

        // 6. Parse and write pane content (layer 1)
        renderPaneContent(leftPane, middlePane, rightPane)

        // 7. Write divider characters
        renderDividers()

        // 8. Write particle glyphs in accent areas (layer 2)
        if (config.enableParticles) {
            renderParticleAccents()
        }

        // 9. Write status bar
        renderStatusBar(statusBar)

        // 10. Generate output
        val rawOutput = if (frameCount == 0L) {
            buffer.renderFull()
        } else {
            buffer.renderDiff()
        }

        // Prepend screen clear on resize to remove old layout artifacts
        val output = if (resized) {
            resized = false
            "\u001B[2J$rawOutput"
        } else {
            rawOutput
        }

        buffer.swapBuffers()
        frameCount++

        return output
    }

    private fun renderSubstrateAccents() {
        for (y in 0 until contentHeight) {
            for (x in accentColumns) {
                if (x >= totalWidth) continue
                val density = substrate.getDensity(x, y)
                val glyph = SubstrateGlyphs.forDensity(density, config.useUnicode)
                val color = if (config.useColor) {
                    AmperePalette.forDensity(density * config.substrateOpacity)
                } else null

                buffer.writeChar(x, y, glyph, color, layer = 0)
            }
        }
    }

    private fun renderPaneContent(
        leftPane: PaneRenderer,
        middlePane: PaneRenderer,
        rightPane: PaneRenderer
    ) {
        // Render each pane
        val leftLines = leftPane.render(leftWidth, contentHeight)
            .fitToHeight(contentHeight, leftWidth)
            .map { it.fitToWidth(leftWidth) }

        val middleLines = middlePane.render(middleWidth, contentHeight)
            .fitToHeight(contentHeight, middleWidth)
            .map { it.fitToWidth(middleWidth) }

        val rightLines = rightPane.render(rightWidth, contentHeight)
            .fitToHeight(contentHeight, rightWidth)
            .map { it.fitToWidth(rightWidth) }

        // Parse ANSI and write into buffer
        val leftParsed = leftLines.map { AnsiCellParser.parseLineToWidth(it, leftWidth) }
        val middleParsed = middleLines.map { AnsiCellParser.parseLineToWidth(it, middleWidth) }
        val rightParsed = rightLines.map { AnsiCellParser.parseLineToWidth(it, rightWidth) }

        // Write at correct column offsets (layer 1)
        buffer.writePaneRegion(0, 0, leftParsed, layer = 1)
        buffer.writePaneRegion(divider1Col + 1, 0, middleParsed, layer = 1)
        buffer.writePaneRegion(divider2Col + 1, 0, rightParsed, layer = 1)
    }

    private fun renderDividers() {
        val dividerChar = if (config.useUnicode) '│' else '|'
        for (y in 0 until contentHeight) {
            // Tint dividers with substrate density color
            val density1 = substrate.getDensity(divider1Col, y)
            val density2 = substrate.getDensity(divider2Col, y)
            val color1 = if (config.useColor) AmperePalette.forDensity(density1) else null
            val color2 = if (config.useColor) AmperePalette.forDensity(density2) else null

            buffer.writeChar(divider1Col, y, dividerChar, color1, layer = 1)
            buffer.writeChar(divider2Col, y, dividerChar, color2, layer = 1)
        }
    }

    private fun renderParticleAccents() {
        for (particle in particles.getParticles()) {
            val x = particle.position.x.roundToInt()
            val y = particle.position.y.roundToInt()

            // Only render in accent areas
            if (x !in accentColumns) continue
            if (y < 0 || y >= contentHeight) continue

            val color = if (config.useColor) {
                when {
                    particle.life > 0.7f -> AmperePalette.SPARK_ACCENT
                    particle.life > 0.4f -> AmperePalette.SUBSTRATE_BRIGHT
                    else -> AmperePalette.SUBSTRATE_MID
                }
            } else null

            buffer.writeChar(x, y, particle.glyph, color, layer = 2)
        }
    }

    private fun renderStatusBar(statusBar: String) {
        val dividerRow = contentHeight
        val statusRow = contentHeight + 1
        val horizontalDivider = if (config.useUnicode) '─' else '-'

        // Horizontal divider line
        val dividerColor = if (config.useColor) AmperePalette.SUBSTRATE_DIM else null
        for (x in 0 until totalWidth) {
            buffer.writeChar(x, dividerRow, horizontalDivider, dividerColor, layer = 3)
        }

        // Status bar text (parse ANSI to preserve color styling)
        val statusCells = AnsiCellParser.parseLineToWidth(statusBar, totalWidth)
        buffer.writePaneRegion(0, statusRow, listOf(statusCells), layer = 3)
    }

    /**
     * Render one frame and return the cell buffer directly (for Mosaic integration).
     *
     * This runs the same 10-step pipeline as [render] but returns the buffer
     * instead of converting to ANSI escape sequences. The buffer can then be
     * converted to Mosaic AnnotatedStrings.
     *
     * @return The cell buffer after rendering, or null if not initialized
     */
    fun renderToBuffer(
        leftPane: PaneRenderer,
        middlePane: PaneRenderer,
        rightPane: PaneRenderer,
        statusBar: String,
        viewState: WatchViewState? = null,
        deltaSeconds: Float = 0.033f
    ): HybridCellBuffer? {
        if (!initialized) initialize()

        // 1. Bridge updates animation from watch state
        substrate = bridge.update(viewState, substrate, deltaSeconds)

        // 2. Update waveform pane with current substrate/flow state
        waveformPane?.update(substrate, flow = null, dt = deltaSeconds)

        // 4. Clear buffer
        buffer.clear()

        // 5-9: Same pipeline as render()
        if (config.enableSubstrate) renderSubstrateAccents()
        renderPaneContent(leftPane, middlePane, rightPane)
        renderDividers()
        if (config.enableParticles) renderParticleAccents()
        renderStatusBar(statusBar)

        frameCount++
        return buffer
    }

    fun hideCursor(): String = "\u001B[?25l"
    fun showCursor(): String = "\u001B[?25h"
}
