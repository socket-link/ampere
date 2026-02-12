package link.socket.ampere.animation.agent

import link.socket.ampere.animation.substrate.Vector2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Layout orientations for agent positioning.
 */
enum class AgentLayoutOrientation {
    HORIZONTAL,
    VERTICAL,
    CIRCULAR,
    CUSTOM
}

/**
 * Manages the visual layer for agent nodes.
 *
 * The AgentLayer handles:
 * - Agent positioning and layout
 * - State transition animations
 * - Shimmer effects during processing
 * - Rendering agent nodes with status text
 */
class AgentLayer(
    private val width: Int,
    private val height: Int,
    private val orientation: AgentLayoutOrientation = AgentLayoutOrientation.HORIZONTAL
) {
    private val agents = mutableMapOf<String, AgentVisualState>()
    private val spawnProgress = mutableMapOf<String, Float>()

    /** All current agents */
    val allAgents: List<AgentVisualState> get() = agents.values.toList()

    /** Number of agents */
    val agentCount: Int get() = agents.size

    /**
     * Add a new agent to the layer.
     */
    fun addAgent(agent: AgentVisualState) {
        agents[agent.id] = agent
        if (agent.state == AgentActivityState.SPAWNING) {
            spawnProgress[agent.id] = 0f
        }
        relayout()
    }

    /**
     * Remove an agent from the layer.
     */
    fun removeAgent(agentId: String) {
        agents.remove(agentId)
        spawnProgress.remove(agentId)
        relayout()
    }

    /**
     * Get an agent by ID.
     */
    fun getAgent(agentId: String): AgentVisualState? = agents[agentId]

    /**
     * Update an agent's state.
     */
    fun updateAgentState(agentId: String, newState: AgentActivityState) {
        agents[agentId]?.let { agent ->
            agents[agentId] = agent.withState(newState)
            if (newState == AgentActivityState.SPAWNING) {
                spawnProgress[agentId] = 0f
            } else {
                spawnProgress.remove(agentId)
            }
        }
    }

    /**
     * Update an agent's status text.
     */
    fun updateAgentStatus(agentId: String, status: String) {
        agents[agentId]?.let { agent ->
            agents[agentId] = agent.withStatus(status)
        }
    }

    /**
     * Update all agents for one animation frame.
     *
     * @param deltaTime Time elapsed in seconds
     * @param shimmerSpeed Speed of shimmer effect
     * @param spawnSpeed Speed of spawn animation
     */
    fun update(deltaTime: Float, shimmerSpeed: Float = 2f, spawnSpeed: Float = 1f) {
        agents.forEach { (id, agent) ->
            var updated = agent

            // Update shimmer for processing agents
            if (agent.state == AgentActivityState.PROCESSING) {
                val newPhase = (agent.pulsePhase + deltaTime * shimmerSpeed) % 1f
                updated = updated.withPulsePhase(newPhase)
            }

            // Update spawn progress
            if (agent.state == AgentActivityState.SPAWNING) {
                val progress = (spawnProgress[id] ?: 0f) + deltaTime * spawnSpeed
                spawnProgress[id] = progress
                if (progress >= 1f) {
                    // Spawning complete, transition to IDLE
                    updated = updated.withState(AgentActivityState.IDLE)
                    spawnProgress.remove(id)
                }
            }

            agents[id] = updated
        }
    }

    /**
     * Recalculate agent positions based on layout.
     */
    fun relayout() {
        val agentList = agents.values.toList()
        val positions = calculateLayout(agentList.size)

        agentList.forEachIndexed { index, agent ->
            agents[agent.id] = agent.withPosition(positions.getOrElse(index) { Vector2.ZERO })
        }
    }

    /**
     * Calculate positions for agents based on layout orientation.
     */
    private fun calculateLayout(count: Int): List<Vector2> {
        if (count == 0) return emptyList()

        return when (orientation) {
            AgentLayoutOrientation.HORIZONTAL -> layoutHorizontal(count)
            AgentLayoutOrientation.VERTICAL -> layoutVertical(count)
            AgentLayoutOrientation.CIRCULAR -> layoutCircular(count)
            AgentLayoutOrientation.CUSTOM -> agents.values.map { it.position }
        }
    }

    private fun layoutHorizontal(count: Int): List<Vector2> {
        val spacing = width / (count + 1)
        val y = height * 0.2f

        return (1..count).map { i ->
            Vector2(i * spacing.toFloat(), y)
        }
    }

    private fun layoutVertical(count: Int): List<Vector2> {
        val spacing = height / (count + 1)
        val x = width * 0.5f

        return (1..count).map { i ->
            Vector2(x, i * spacing.toFloat())
        }
    }

    private fun layoutCircular(count: Int): List<Vector2> {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) * 0.35f
        val angleStep = 2 * PI / count

        return (0 until count).map { i ->
            val angle = i * angleStep - PI / 2 // Start from top
            Vector2(
                centerX + (cos(angle) * radius).toFloat(),
                centerY + (sin(angle) * radius).toFloat()
            )
        }
    }

    /**
     * Set a custom position for an agent.
     */
    fun setAgentPosition(agentId: String, position: Vector2) {
        agents[agentId]?.let { agent ->
            agents[agentId] = agent.withPosition(position)
        }
    }

    /**
     * Get spawn progress for an agent (0.0-1.0).
     */
    fun getSpawnProgress(agentId: String): Float = spawnProgress[agentId] ?: 1f

    /**
     * Create spawn animation for an agent.
     */
    fun startSpawn(agentId: String) {
        agents[agentId]?.let { agent ->
            agents[agentId] = agent.withState(AgentActivityState.SPAWNING)
            spawnProgress[agentId] = 0f
        }
    }

    /**
     * Clear all agents.
     */
    fun clear() {
        agents.clear()
        spawnProgress.clear()
    }
}

/**
 * Renders agent layer to text output.
 */
class AgentLayerRenderer(
    private val useUnicode: Boolean = true,
    private val showStatusText: Boolean = true
) {
    /**
     * Render all agents to a list of positioned render items.
     */
    fun render(layer: AgentLayer): List<AgentRenderItem> {
        return layer.allAgents.map { agent ->
            renderAgent(agent, layer.getSpawnProgress(agent.id))
        }
    }

    /**
     * Render a single agent.
     */
    private fun renderAgent(agent: AgentVisualState, spawnProgress: Float): AgentRenderItem {
        val glyph = when (agent.state) {
            AgentActivityState.SPAWNING -> AgentGlyphs.spawningGlyph(spawnProgress, useUnicode)
            AgentActivityState.PROCESSING -> getShimmerGlyph(agent)
            else -> agent.getPrimaryGlyph(useUnicode)
        }

        val color = when (agent.state) {
            AgentActivityState.PROCESSING -> getShimmerColor(agent)
            else -> AgentColors.forState(agent.state)
        }

        val suffix = agent.getAccentSuffix(useUnicode)

        // Build the node display
        val nodeDisplay = buildString {
            append(color)
            append(glyph)
            append(suffix)
            append(AgentColors.RESET)
            append(" ")
            append(agent.name)
        }

        // Build status line
        val statusDisplay = if (showStatusText && agent.statusText.isNotEmpty()) {
            buildString {
                append("\u001B[38;5;240m") // Gray
                append("\u2514\u2500 ") // └─
                append(agent.statusText)
                append(AgentColors.RESET)
            }
        } else null

        return AgentRenderItem(
            agentId = agent.id,
            position = agent.position,
            nodeDisplay = nodeDisplay,
            statusDisplay = statusDisplay,
            roleDisplay = agent.role,
            stateColor = color
        )
    }

    /**
     * Get shimmer glyph based on pulse phase.
     */
    private fun getShimmerGlyph(agent: AgentVisualState): Char {
        // Shimmer between different brightness levels
        val phase = agent.pulsePhase
        return when {
            phase < 0.5f -> if (useUnicode) '\u25C9' else '@'
            else -> if (useUnicode) '\u25CE' else 'o'
        }
    }

    /**
     * Get shimmer color based on pulse phase.
     */
    private fun getShimmerColor(agent: AgentVisualState): String {
        val phase = agent.pulsePhase
        return when {
            phase < 0.25f -> "\u001B[38;5;226m"  // Bright gold
            phase < 0.5f -> "\u001B[38;5;228m"   // Light gold
            phase < 0.75f -> "\u001B[38;5;226m"  // Bright gold
            else -> "\u001B[38;5;220m"           // Gold
        }
    }

    /**
     * Render to a character grid.
     *
     * @param layer The agent layer to render
     * @param gridWidth Grid width
     * @param gridHeight Grid height
     * @return List of rows
     */
    fun renderToGrid(layer: AgentLayer, gridWidth: Int, gridHeight: Int): List<String> {
        val items = render(layer)
        val grid = Array(gridHeight) { CharArray(gridWidth) { ' ' } }

        items.forEach { item ->
            val x = item.position.x.toInt().coerceIn(0, gridWidth - 1)
            val y = item.position.y.toInt().coerceIn(0, gridHeight - 1)

            // Place node at position
            val nodeText = stripAnsi(item.nodeDisplay)
            if (y < gridHeight && x + nodeText.length <= gridWidth) {
                nodeText.forEachIndexed { i, ch -> grid[y][x + i] = ch }
            }

            // Place status below if present
            item.statusDisplay?.let { status ->
                val statusY = y + 1
                if (statusY < gridHeight) {
                    val statusText = stripAnsi(status)
                    if (x + statusText.length <= gridWidth) {
                        statusText.forEachIndexed { i, ch -> grid[statusY][x + i] = ch }
                    }
                }
            }
        }

        return grid.map { it.concatToString() }
    }

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;]*m"), "")
    }
}

/**
 * Rendered output for a single agent.
 */
data class AgentRenderItem(
    val agentId: String,
    val position: Vector2,
    val nodeDisplay: String,
    val statusDisplay: String?,
    val roleDisplay: String,
    val stateColor: String
)
