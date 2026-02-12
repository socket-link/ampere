package link.socket.ampere.animation.flow

import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.particle.Particle
import link.socket.ampere.animation.particle.ParticleType
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.animation.substrate.Vector2
import kotlin.math.roundToInt

/**
 * Manages flow connections between agents and animates task handoffs.
 *
 * FlowLayer coordinates the visual representation of agent-to-agent
 * communication. When a handoff occurs, it:
 * 1. Creates a path between the agents
 * 2. Animates a token traveling along the path
 * 3. Leaves trail particles
 * 4. Updates substrate density along the path
 *
 * @property width Layer width in characters
 * @property height Layer height in characters
 */
class FlowLayer(
    val width: Int,
    val height: Int
) {
    private val connections = mutableMapOf<String, FlowConnection>()
    private var trailParticles = mutableListOf<Particle>()

    /** Number of active connections */
    val connectionCount: Int get() = connections.size

    /** Number of trail particles */
    val particleCount: Int get() = trailParticles.size

    /** All connections */
    val allConnections: List<FlowConnection> get() = connections.values.toList()

    /** All trail particles */
    val allTrailParticles: List<Particle> get() = trailParticles.toList()

    /**
     * Create a connection between two agents.
     *
     * @param sourceAgentId ID of the source agent
     * @param targetAgentId ID of the target agent
     * @param sourcePosition Position of the source agent
     * @param targetPosition Position of the target agent
     * @return The created connection ID
     */
    fun createConnection(
        sourceAgentId: String,
        targetAgentId: String,
        sourcePosition: Vector2,
        targetPosition: Vector2
    ): String {
        val id = "$sourceAgentId->$targetAgentId"
        val path = FlowPath.calculatePath(sourcePosition, targetPosition)

        val connection = FlowConnection(
            id = id,
            sourceAgentId = sourceAgentId,
            targetAgentId = targetAgentId,
            path = path
        )

        connections[id] = connection
        return id
    }

    /**
     * Create connections from an AgentLayer.
     *
     * @param agentLayer The agent layer to read positions from
     * @param pairs List of (sourceId, targetId) pairs to connect
     */
    fun createConnectionsFromAgents(
        agentLayer: AgentLayer,
        pairs: List<Pair<String, String>>
    ) {
        pairs.forEach { (sourceId, targetId) ->
            val source = agentLayer.getAgent(sourceId)
            val target = agentLayer.getAgent(targetId)

            if (source != null && target != null) {
                createConnection(sourceId, targetId, source.position, target.position)
            }
        }
    }

    /**
     * Get a connection by ID.
     */
    fun getConnection(id: String): FlowConnection? = connections[id]

    /**
     * Get a connection by agent pair.
     */
    fun getConnection(sourceAgentId: String, targetAgentId: String): FlowConnection? {
        return connections["$sourceAgentId->$targetAgentId"]
    }

    /**
     * Remove a connection.
     */
    fun removeConnection(id: String) {
        connections.remove(id)
    }

    /**
     * Start a handoff animation on a connection.
     *
     * @param connectionId The connection to animate
     * @return true if handoff started, false if connection not found
     */
    fun startHandoff(connectionId: String): Boolean {
        val connection = connections[connectionId] ?: return false

        if (connection.path.isEmpty()) return false

        val sourcePosition = connection.path.first()
        connections[connectionId] = connection.startHandoff(sourcePosition)
            .withState(FlowState.TRANSMITTING)

        return true
    }

    /**
     * Start a handoff between two agents.
     */
    fun startHandoff(sourceAgentId: String, targetAgentId: String): Boolean {
        return startHandoff("$sourceAgentId->$targetAgentId")
    }

    /**
     * Update all connections and animations.
     *
     * @param deltaTime Time elapsed in seconds
     * @param transmissionSpeed Speed of token movement (0-1 per second)
     * @param trailSpawnRate How often to spawn trail particles
     * @param trailDecayRate How fast trail particles fade
     */
    fun update(
        deltaTime: Float,
        transmissionSpeed: Float = 0.5f,
        trailSpawnRate: Float = 0.1f,
        trailDecayRate: Float = 0.8f
    ) {
        // Update each connection
        connections.keys.toList().forEach { id ->
            val connection = connections[id] ?: return@forEach

            when (connection.state) {
                FlowState.DORMANT -> {
                    // No animation needed
                }

                FlowState.ACTIVATING -> {
                    // Brief activation phase before transmission
                    val newProgress = connection.progress + deltaTime * 2f
                    if (newProgress >= 0.1f) {
                        connections[id] = connection
                            .withProgress(0f)
                            .withState(FlowState.TRANSMITTING)
                    } else {
                        connections[id] = connection.withProgress(newProgress)
                    }
                }

                FlowState.TRANSMITTING -> {
                    val easedProgress = FlowEasing.easeInOut(connection.progress)
                    val newProgress = connection.progress + deltaTime * transmissionSpeed

                    if (newProgress >= 1f) {
                        // Transmission complete
                        connections[id] = connection.completeHandoff()
                    } else {
                        // Update token position
                        val newPosition = FlowPath.positionAtProgress(connection.path, easedProgress)
                        val currentToken = connection.taskToken

                        if (currentToken != null) {
                            // Spawn trail particle
                            if (kotlin.random.Random.nextFloat() < trailSpawnRate) {
                                val trailParticle = Particle(
                                    position = currentToken.position,
                                    velocity = Vector2.ZERO,
                                    life = 1f,
                                    type = ParticleType.TRAIL,
                                    glyph = TaskToken.Companion.Glyphs.TRAIL_GRADIENT[0]
                                )
                                trailParticles.add(trailParticle)
                            }

                            val updatedToken = currentToken.copy(
                                position = newPosition,
                                glyph = TaskToken.Companion.Glyphs.forProgress(newProgress)
                            )
                            connections[id] = connection
                                .withProgress(newProgress)
                                .withToken(updatedToken)
                        } else {
                            connections[id] = connection.withProgress(newProgress)
                        }
                    }
                }

                FlowState.RECEIVED -> {
                    // Hold received state briefly, then transition back to dormant
                    val newProgress = connection.progress - deltaTime * 0.5f
                    if (newProgress <= 0f) {
                        connections[id] = connection.reset()
                    } else {
                        connections[id] = connection.copy(progress = newProgress)
                    }
                }
            }
        }

        // Update trail particles
        trailParticles = trailParticles.mapNotNull { particle ->
            val newLife = particle.life - (deltaTime * trailDecayRate)
            if (newLife <= 0f) {
                null
            } else {
                // Update glyph based on life
                val gradient = TaskToken.Companion.Glyphs.TRAIL_GRADIENT
                val glyphIndex = ((1f - newLife) * (gradient.size - 1)).toInt()
                    .coerceIn(0, gradient.size - 1)
                particle.copy(
                    life = newLife,
                    glyph = gradient[glyphIndex]
                )
            }
        }.toMutableList()
    }

    /**
     * Update substrate density along active paths.
     *
     * Note: This modifies the substrate in place (setDensity mutates the underlying array).
     *
     * @param substrate The substrate to update
     * @param influence How much active paths affect density
     * @return The same substrate reference (modified in place)
     */
    fun updateSubstrate(substrate: SubstrateState, influence: Float = 0.2f): SubstrateState {
        connections.values.filter { it.isActive }.forEach { connection ->
            connection.path.forEach { point ->
                val x = point.x.roundToInt().coerceIn(0, substrate.width - 1)
                val y = point.y.roundToInt().coerceIn(0, substrate.height - 1)

                val currentDensity = substrate.getDensity(x, y)
                val boost = when (connection.state) {
                    FlowState.TRANSMITTING -> influence
                    FlowState.ACTIVATING -> influence * 0.5f
                    FlowState.RECEIVED -> influence * 0.3f
                    FlowState.DORMANT -> 0f
                }
                substrate.setDensity(x, y, (currentDensity + boost).coerceIn(0f, 1f))
            }

            // Extra density at token position
            connection.taskToken?.let { token ->
                val x = token.position.x.roundToInt().coerceIn(0, substrate.width - 1)
                val y = token.position.y.roundToInt().coerceIn(0, substrate.height - 1)
                val currentDensity = substrate.getDensity(x, y)
                substrate.setDensity(x, y, (currentDensity + influence * 2).coerceIn(0f, 1f))
            }
        }

        return substrate
    }

    /**
     * Get all active transmitting connections.
     */
    fun getActiveConnections(): List<FlowConnection> {
        return connections.values.filter { it.isActive }
    }

    /**
     * Get all connections in transmitting state.
     */
    fun getTransmittingConnections(): List<FlowConnection> {
        return connections.values.filter { it.state == FlowState.TRANSMITTING }
    }

    /**
     * Clear all connections and particles.
     */
    fun clear() {
        connections.clear()
        trailParticles.clear()
    }

    /**
     * Clear all trail particles.
     */
    fun clearTrails() {
        trailParticles.clear()
    }

    /**
     * Update path for a connection (e.g., if agents moved).
     */
    fun updateConnectionPath(
        connectionId: String,
        sourcePosition: Vector2,
        targetPosition: Vector2
    ) {
        val connection = connections[connectionId] ?: return
        val newPath = FlowPath.calculatePath(sourcePosition, targetPosition)
        connections[connectionId] = connection.withPath(newPath)
    }
}
