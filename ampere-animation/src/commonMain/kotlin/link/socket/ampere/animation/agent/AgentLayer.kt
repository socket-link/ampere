package link.socket.ampere.animation.agent

import link.socket.ampere.animation.math.Vector3
import link.socket.ampere.animation.substrate.Vector2
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Layout orientations for agent positioning.
 */
enum class AgentLayoutOrientation {
    HORIZONTAL,
    VERTICAL,
    /** Agents on a circle in the XZ plane at Y=0 */
    CIRCULAR,
    /** Agents distributed on a sphere surface */
    SPHERE,
    /** Agents grouped by role, with cluster centers at different depths */
    CLUSTERED,
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
     * Update an agent's cognitive phase and optional progress.
     */
    fun updateAgentCognitivePhase(agentId: String, phase: CognitivePhase, progress: Float = 0f) {
        agents[agentId]?.let { agent ->
            agents[agentId] = agent.withCognitivePhase(phase, progress)
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
     *
     * For 3D-aware layouts (CIRCULAR, SPHERE, CLUSTERED), both [AgentVisualState.position]
     * and [AgentVisualState.position3D] are updated. For 2D-only layouts (HORIZONTAL, VERTICAL),
     * the 3D position is derived with Y=0 and Z mapped from the 2D Y coordinate.
     */
    fun relayout() {
        val agentList = agents.values.toList()

        when (orientation) {
            AgentLayoutOrientation.SPHERE,
            AgentLayoutOrientation.CLUSTERED -> {
                val positions3D = calculateLayout3D(agentList)
                agentList.forEachIndexed { index, agent ->
                    agents[agent.id] = agent.withPosition3D(
                        positions3D.getOrElse(index) { Vector3.ZERO }
                    )
                }
            }
            AgentLayoutOrientation.CIRCULAR -> {
                // CIRCULAR now distributes in XZ plane at Y=0
                val positions3D = layoutCircular3D(agentList.size)
                agentList.forEachIndexed { index, agent ->
                    agents[agent.id] = agent.withPosition3D(
                        positions3D.getOrElse(index) { Vector3.ZERO }
                    )
                }
            }
            else -> {
                val positions = calculateLayout2D(agentList.size)
                agentList.forEachIndexed { index, agent ->
                    agents[agent.id] = agent.withPosition(
                        positions.getOrElse(index) { Vector2.ZERO }
                    )
                }
            }
        }
    }

    /**
     * Calculate 2D positions for agents.
     */
    private fun calculateLayout2D(count: Int): List<Vector2> {
        if (count == 0) return emptyList()

        return when (orientation) {
            AgentLayoutOrientation.HORIZONTAL -> layoutHorizontal(count)
            AgentLayoutOrientation.VERTICAL -> layoutVertical(count)
            AgentLayoutOrientation.CUSTOM -> agents.values.map { it.position }
            else -> agents.values.map { it.position }
        }
    }

    /**
     * Calculate 3D positions for SPHERE and CLUSTERED layouts.
     */
    private fun calculateLayout3D(agentList: List<AgentVisualState>): List<Vector3> {
        if (agentList.isEmpty()) return emptyList()

        return when (orientation) {
            AgentLayoutOrientation.SPHERE -> layoutSphere(agentList.size)
            AgentLayoutOrientation.CLUSTERED -> layoutClustered(agentList)
            else -> agentList.map { it.position3D }
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

    /**
     * Distribute agents on a circle in the XZ plane at Y=0.
     * The 2D position is derived as (X, Z) from the 3D position.
     */
    private fun layoutCircular3D(count: Int): List<Vector3> {
        val radius = minOf(width, height) * 0.35f
        val angleStep = 2 * PI / count

        return (0 until count).map { i ->
            val angle = i * angleStep - PI / 2 // Start from top
            Vector3(
                (cos(angle) * radius).toFloat(),
                0f,
                (sin(angle) * radius).toFloat()
            )
        }
    }

    /**
     * Distribute agents on a sphere surface using the Fibonacci sphere algorithm.
     * Provides near-uniform distribution with meaningful Y (height) variation.
     */
    private fun layoutSphere(count: Int): List<Vector3> {
        val radius = minOf(width, height) * 0.35f
        val goldenRatio = (1f + sqrt(5f)) / 2f

        return (0 until count).map { i ->
            // Fibonacci sphere: evenly distribute points on a sphere
            val theta = 2f * PI.toFloat() * i / goldenRatio
            val phi = acos(1f - 2f * (i + 0.5f) / count)

            Vector3(
                (sin(phi) * cos(theta) * radius).toFloat(),
                (cos(phi) * radius).toFloat(),
                (sin(phi) * sin(theta) * radius).toFloat()
            )
        }
    }

    /**
     * Group agents by role, placing each role cluster at a different Z-depth.
     * Within each cluster, agents are arranged in a small circle.
     */
    private fun layoutClustered(agentList: List<AgentVisualState>): List<Vector3> {
        val roleGroups = agentList.groupBy { it.role }
        val clusterCount = roleGroups.size
        val result = mutableMapOf<String, Vector3>()

        roleGroups.entries.forEachIndexed { clusterIndex, (_, groupAgents) ->
            // Distribute cluster centers at different Z-depths
            val clusterZ = if (clusterCount <= 1) 0f
                else (clusterIndex.toFloat() / (clusterCount - 1) - 0.5f) * minOf(width, height) * 0.5f

            // Spread clusters along X axis
            val clusterX = if (clusterCount <= 1) 0f
                else (clusterIndex.toFloat() / (clusterCount - 1) - 0.5f) * width * 0.4f

            // Arrange agents in a small circle within the cluster
            val clusterRadius = minOf(width, height) * 0.1f
            val angleStep = 2 * PI / groupAgents.size

            groupAgents.forEachIndexed { agentIndex, agent ->
                val angle = agentIndex * angleStep
                result[agent.id] = Vector3(
                    clusterX + (cos(angle) * clusterRadius).toFloat(),
                    0f, // Y (height) left at 0 for waveform to control
                    clusterZ + (sin(angle) * clusterRadius).toFloat()
                )
            }
        }

        return agentList.map { result[it.id] ?: Vector3.ZERO }
    }

    /**
     * Set a custom 2D position for an agent.
     */
    fun setAgentPosition(agentId: String, position: Vector2) {
        agents[agentId]?.let { agent ->
            agents[agentId] = agent.withPosition(position)
        }
    }

    /**
     * Set a custom 3D position for an agent.
     */
    fun setAgentPosition3D(agentId: String, position3D: Vector3) {
        agents[agentId]?.let { agent ->
            agents[agentId] = agent.withPosition3D(position3D)
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
