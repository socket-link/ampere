package link.socket.ampere.cli.animation.render

import link.socket.phosphor.color.AnsiColorAdapter
import link.socket.phosphor.color.CognitiveColorModel
import link.socket.phosphor.color.FlowColorState
import link.socket.phosphor.color.ParticleColorKind
import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.choreography.AgentLayerRenderer
import link.socket.phosphor.field.FlowLayer
import link.socket.phosphor.field.FlowLayerRenderer
import link.socket.ampere.cli.animation.logo.LogoCrystallizer
import link.socket.ampere.cli.animation.logo.LogoRenderer
import link.socket.phosphor.field.ParticleRenderer
import link.socket.phosphor.field.ParticleSystem
import link.socket.phosphor.field.BasicSubstrateRenderer
import link.socket.phosphor.field.ColoredSubstrateRenderer
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.render.RenderCell
import link.socket.phosphor.render.RenderLayer
import link.socket.phosphor.signal.AgentActivityState
import kotlin.math.roundToInt

/**
 * Color palette for Ampere TUI.
 */
object AmperePalette {
    private val colorModel = CognitiveColorModel
    private val ansiAdapter = AnsiColorAdapter()

    // Substrate colors sampled from Phosphor's shared flow-intensity ramp.
    val SUBSTRATE_DIM = ansiAdapter.foreground(colorModel.flowIntensityRamp.sample(0.2f))
    val SUBSTRATE_MID = ansiAdapter.foreground(colorModel.flowIntensityRamp.sample(0.55f))
    val SUBSTRATE_BRIGHT = ansiAdapter.foreground(colorModel.flowIntensityRamp.sample(0.9f))

    // Agent colors
    val AGENT_IDLE = ansiAdapter.foreground(colorModel.agentActivityColors.getValue(AgentActivityState.IDLE))
    val AGENT_ACTIVE = ansiAdapter.foreground(colorModel.agentActivityColors.getValue(AgentActivityState.ACTIVE))
    val AGENT_PROCESSING = ansiAdapter.foreground(colorModel.agentActivityColors.getValue(AgentActivityState.PROCESSING))
    val AGENT_COMPLETE = ansiAdapter.foreground(colorModel.agentActivityColors.getValue(AgentActivityState.COMPLETE))

    // Flow colors
    val FLOW_DORMANT = ansiAdapter.foreground(colorModel.flowStateColors.getValue(FlowColorState.DORMANT))
    val FLOW_ACTIVE = ansiAdapter.foreground(colorModel.flowStateColors.getValue(FlowColorState.ACTIVATING))
    val FLOW_TOKEN = ansiAdapter.foreground(colorModel.particleColors.getValue(ParticleColorKind.TRAIL))

    // Accent colors
    val SPARK_ACCENT = ansiAdapter.foreground(colorModel.particleColors.getValue(ParticleColorKind.SPARK))
    val SUCCESS_GREEN = ansiAdapter.foreground(colorModel.agentActivityColors.getValue(AgentActivityState.COMPLETE))
    val LOGO_BOLT = ansiAdapter.foreground(colorModel.roleColorFor("reasoning"))
    val LOGO_TEXT = ansiAdapter.foreground(colorModel.roleColorFor("coordinator"))

    // Reset
    val RESET = AnsiColorAdapter.RESET

    /**
     * Get substrate color for density value.
     */
    fun forDensity(density: Float): String = when {
        density < 0.3f -> SUBSTRATE_DIM
        density < 0.6f -> SUBSTRATE_MID
        else -> SUBSTRATE_BRIGHT
    }
}

/**
 * Composites multiple animation layers into a unified terminal output.
 *
 * CompositeRenderer handles:
 * - Layer compositing (substrate → flow → particles → agents → logo)
 * - ANSI color mapping
 * - Terminal size adaptation
 * - Frame buffering for flicker-free output
 *
 * @property width Render width in characters
 * @property height Render height in characters
 * @property useColor Whether to use ANSI colors
 * @property useUnicode Whether to use Unicode glyphs
 */
class CompositeRenderer(
    private val width: Int,
    private val height: Int,
    private val useColor: Boolean = true,
    private val useUnicode: Boolean = true
) {
    private val substrateRenderer = if (useColor) {
        ColoredSubstrateRenderer()
    } else {
        BasicSubstrateRenderer(useUnicode)
    }

    private val agentRenderer = AgentLayerRenderer(useUnicode, showStatusText = true)
    private val flowRenderer = FlowLayerRenderer(useUnicode, showDormantConnections = false)
    private val particleRenderer = ParticleRenderer(width, height, useUnicode)
    private val logoRenderer = LogoRenderer(width, height, useUnicode)

    // Double buffer for flicker-free rendering
    private var currentBuffer: Array<Array<RenderCell>> = createBuffer()
    private var backBuffer: Array<Array<RenderCell>> = createBuffer()

    private fun createBuffer(): Array<Array<RenderCell>> {
        return Array(height) { Array(width) { RenderCell(' ', null, RenderLayer.SUBSTRATE) } }
    }

    /**
     * Render all layers to a string buffer.
     *
     * @param substrate Background substrate (optional)
     * @param particles Particle system (optional)
     * @param agents Agent layer (optional)
     * @param flow Flow layer (optional)
     * @param logoCrystallizer Logo crystallization state (optional)
     * @param statusBar Bottom status bar text (optional)
     * @return Complete terminal output string
     */
    fun render(
        substrate: SubstrateState? = null,
        particles: ParticleSystem? = null,
        agents: AgentLayer? = null,
        flow: FlowLayer? = null,
        logoCrystallizer: LogoCrystallizer? = null,
        statusBar: String? = null
    ): String {
        // Clear back buffer
        clearBuffer(backBuffer)

        // Render layers in order (lowest priority first)
        substrate?.let { renderSubstrate(it, backBuffer) }
        flow?.let { renderFlow(it, backBuffer) }
        particles?.let { renderParticles(it, backBuffer) }
        agents?.let { renderAgents(it, backBuffer) }
        logoCrystallizer?.let { renderLogo(it, backBuffer) }

        // Swap buffers
        val temp = currentBuffer
        currentBuffer = backBuffer
        backBuffer = temp

        // Convert to output string
        return bufferToString(currentBuffer, statusBar)
    }

    /**
     * Render only changed cells (differential update).
     * Returns ANSI escape sequences to update only changed positions.
     */
    fun renderDiff(
        substrate: SubstrateState? = null,
        particles: ParticleSystem? = null,
        agents: AgentLayer? = null,
        flow: FlowLayer? = null,
        logoCrystallizer: LogoCrystallizer? = null
    ): String {
        // Render to back buffer
        clearBuffer(backBuffer)
        substrate?.let { renderSubstrate(it, backBuffer) }
        flow?.let { renderFlow(it, backBuffer) }
        particles?.let { renderParticles(it, backBuffer) }
        agents?.let { renderAgents(it, backBuffer) }
        logoCrystallizer?.let { renderLogo(it, backBuffer) }

        // Build differential output
        val diff = buildString {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val newCell = backBuffer[y][x]
                    val oldCell = currentBuffer[y][x]

                    if (newCell != oldCell) {
                        // Move cursor and write new cell
                        append("\u001B[${y + 1};${x + 1}H")
                        if (useColor && newCell.color != null) {
                            append(newCell.color)
                            append(newCell.char)
                            append(AmperePalette.RESET)
                        } else {
                            append(newCell.char)
                        }
                    }
                }
            }
        }

        // Swap buffers
        val temp = currentBuffer
        currentBuffer = backBuffer
        backBuffer = temp

        return diff
    }

    private fun clearBuffer(buffer: Array<Array<RenderCell>>) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer[y][x] = RenderCell(' ', null, RenderLayer.SUBSTRATE)
            }
        }
    }

    private fun renderSubstrate(substrate: SubstrateState, buffer: Array<Array<RenderCell>>) {
        for (y in 0 until minOf(height, substrate.height)) {
            for (x in 0 until minOf(width, substrate.width)) {
                val density = substrate.getDensity(x, y)
                val glyph = link.socket.phosphor.field.SubstrateGlyphs.forDensity(
                    density,
                    useUnicode
                )
                val color = if (useColor) AmperePalette.forDensity(density) else null
                buffer[y][x] = RenderCell(glyph, color, RenderLayer.SUBSTRATE)
            }
        }
    }

    private fun renderFlow(flow: FlowLayer, buffer: Array<Array<RenderCell>>) {
        val items = flowRenderer.render(flow)

        items.forEach { item ->
            // Render path
            item.pathChars.forEach { (pos, char) ->
                val x = pos.x.roundToInt()
                val y = pos.y.roundToInt()
                if (x in 0 until width && y in 0 until height) {
                    val existingCell = buffer[y][x]
                    // Only overwrite if this layer has higher priority
                    if (RenderLayer.FLOW.priority >= existingCell.layer.priority) {
                        buffer[y][x] = RenderCell(char, item.color, RenderLayer.FLOW)
                    }
                }
            }

            // Render token (highest priority in flow layer)
            val pos = item.tokenPosition
            val ch = item.tokenChar
            if (pos != null && ch != null) {
                val x = pos.x.roundToInt()
                val y = pos.y.roundToInt()
                if (x in 0 until width && y in 0 until height) {
                    buffer[y][x] = RenderCell(
                        ch,
                        AmperePalette.FLOW_TOKEN,
                        RenderLayer.FLOW
                    )
                }
            }
        }
    }

    private fun renderParticles(particles: ParticleSystem, buffer: Array<Array<RenderCell>>) {
        particles.getParticles().forEach { particle ->
            val x = particle.position.x.roundToInt()
            val y = particle.position.y.roundToInt()

            if (x in 0 until width && y in 0 until height) {
                val existingCell = buffer[y][x]
                if (RenderLayer.PARTICLES.priority >= existingCell.layer.priority) {
                    val color = when {
                        particle.life > 0.7f -> AmperePalette.SPARK_ACCENT
                        particle.life > 0.4f -> AmperePalette.SUBSTRATE_BRIGHT
                        else -> AmperePalette.SUBSTRATE_MID
                    }
                    buffer[y][x] = RenderCell(particle.glyph, color, RenderLayer.PARTICLES)
                }
            }
        }
    }

    private fun renderAgents(agents: AgentLayer, buffer: Array<Array<RenderCell>>) {
        val items = agentRenderer.render(agents)

        items.forEach { item ->
            val x = item.position.x.roundToInt()
            val y = item.position.y.roundToInt()

            // Render the node display (glyph + name)
            val nodeDisplay = item.nodeDisplay
            for (i in nodeDisplay.indices) {
                val px = x + i
                if (px in 0 until width && y in 0 until height) {
                    buffer[y][px] = RenderCell(
                        nodeDisplay[i],
                        item.stateColor,
                        RenderLayer.AGENTS
                    )
                }
            }

            // Render status below if present
            item.statusDisplay?.let { status ->
                val statusY = y + 1
                if (statusY < height) {
                    for (i in status.indices) {
                        val px = x + i
                        if (px in 0 until width) {
                            buffer[statusY][px] = RenderCell(
                                status[i],
                                AmperePalette.SUBSTRATE_MID,
                                RenderLayer.AGENTS
                            )
                        }
                    }
                }
            }
        }
    }

    private fun renderLogo(crystallizer: LogoCrystallizer, buffer: Array<Array<RenderCell>>) {
        crystallizer.getVisibleGlyphs().forEach { glyph ->
            val x = glyph.position.x.roundToInt()
            val y = glyph.position.y.roundToInt()

            if (x in 0 until width && y in 0 until height) {
                val color = when (glyph.glyph) {
                    '⚡' -> AmperePalette.LOGO_BOLT
                    in 'A'..'Z' -> AmperePalette.LOGO_TEXT
                    else -> AmperePalette.SUBSTRATE_BRIGHT
                }
                buffer[y][x] = RenderCell(glyph.glyph, color, RenderLayer.UI_OVERLAY)
            }
        }
    }

    private fun bufferToString(buffer: Array<Array<RenderCell>>, statusBar: String?): String {
        return buildString {
            // Clear screen and position at home
            append("\u001B[2J")
            append("\u001B[H")

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val cell = buffer[y][x]
                    if (useColor && cell.color != null) {
                        append(cell.color)
                        append(cell.char)
                        append(AmperePalette.RESET)
                    } else {
                        append(cell.char)
                    }
                }
                if (y < height - 1) {
                    append("\n")
                }
            }

            // Status bar at bottom
            statusBar?.let { bar ->
                append("\n")
                append(AmperePalette.SUBSTRATE_DIM)
                append(bar.take(width).padEnd(width))
                append(AmperePalette.RESET)
            }
        }
    }

    /**
     * Generate ANSI escape sequence to clear screen.
     */
    fun clearScreen(): String = "\u001B[2J\u001B[H"

    /**
     * Generate ANSI escape sequence to hide cursor.
     */
    fun hideCursor(): String = "\u001B[?25l"

    /**
     * Generate ANSI escape sequence to show cursor.
     */
    fun showCursor(): String = "\u001B[?25h"

    /**
     * Get recommended terminal size message.
     */
    fun getSizeRecommendation(): String? {
        return if (width < 80 || height < 24) {
            "Terminal size (${width}x${height}) is below recommended 80x24. Some elements may not display correctly."
        } else {
            null
        }
    }

    companion object {
        /**
         * Create a renderer for the given terminal dimensions.
         */
        fun forTerminal(
            width: Int,
            height: Int,
            useColor: Boolean = true,
            useUnicode: Boolean = true
        ): CompositeRenderer {
            return CompositeRenderer(
                width = width.coerceAtLeast(40),
                height = height.coerceAtLeast(10),
                useColor = useColor,
                useUnicode = useUnicode
            )
        }

        /**
         * Check if the terminal supports 256 colors.
         */
        fun supports256Colors(): Boolean {
            val term = System.getenv("TERM") ?: return false
            return term.contains("256color") || term.contains("truecolor") ||
                term == "xterm-direct" || term == "tmux-256color"
        }

        /**
         * Check if the terminal supports Unicode.
         */
        fun supportsUnicode(): Boolean {
            val lang = System.getenv("LANG") ?: ""
            val lcAll = System.getenv("LC_ALL") ?: ""
            return lang.contains("UTF-8", ignoreCase = true) ||
                lcAll.contains("UTF-8", ignoreCase = true)
        }
    }
}
