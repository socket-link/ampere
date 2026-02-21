package link.socket.ampere.animation.waveform

import link.socket.ampere.animation.agent.AgentActivityState
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentVisualState
import link.socket.ampere.animation.flow.FlowLayer
import link.socket.ampere.animation.math.Vector3
import link.socket.ampere.animation.substrate.SubstrateState
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A heightmap surface that represents the cognitive field as terrain.
 *
 * Imagine looking at the brain from slightly above -- the surface is the
 * "cortical surface" where each region's height corresponds to its activation level.
 * Agents are mountains. Idle regions are plains. Active thought creates ridges
 * that connect communicating agents, like neural pathways becoming visible.
 *
 * The heightmap samples the SubstrateState density field and adds Gaussian peaks
 * at agent positions, with height proportional to activity level.
 *
 * @param gridWidth Resolution of the heightmap (may differ from screen width)
 * @param gridDepth Resolution in the Z dimension (depth into screen)
 * @param worldWidth Physical width of the surface in world units
 * @param worldDepth Physical depth of the surface in world units
 */
class CognitiveWaveform(
    val gridWidth: Int = 40,
    val gridDepth: Int = 30,
    val worldWidth: Float = 20f,
    val worldDepth: Float = 15f
) {
    /** Height values at each grid point. Updated each frame. */
    val heights: FloatArray = FloatArray(gridWidth * gridDepth)

    /** Target heights for smooth interpolation. */
    private val targetHeights: FloatArray = FloatArray(gridWidth * gridDepth)

    /** Speed of temporal smoothing. Higher = snappier transitions. */
    private val smoothingSpeed: Float = 4f

    /** Base height from substrate noise. */
    private val baseHeightScale: Float = 0.5f

    /** Peak height multiplier for agent activity. */
    private val agentPeakScale: Float = 3f

    /** Default influence radius for agent Gaussian peaks (in world units). */
    private val defaultInfluenceRadius: Float = 3f

    /** Height multiplier for flow ridges between connected agents. */
    private val ridgeScale: Float = 1.5f

    /** Width of flow ridges (perpendicular falloff in world units). */
    private val ridgeWidth: Float = 1.5f

    /**
     * Update the heightmap from current cognitive state.
     *
     * @param substrate Current substrate density field (Perlin noise base)
     * @param agents Current agent positions and states
     * @param flow Flow connections between agents (optional)
     * @param dt Delta time for smooth transitions
     */
    fun update(
        substrate: SubstrateState,
        agents: AgentLayer,
        flow: FlowLayer?,
        dt: Float
    ) {
        // 1. Compute target heights
        computeTargetHeights(substrate, agents, flow)

        // 2. Smooth interpolation from current to target
        val lerpFactor = (dt * smoothingSpeed).coerceIn(0f, 1f)
        for (i in heights.indices) {
            heights[i] = heights[i] + (targetHeights[i] - heights[i]) * lerpFactor
        }
    }

    /**
     * Get height at a specific grid coordinate.
     */
    fun heightAt(gx: Int, gz: Int): Float {
        if (gx < 0 || gx >= gridWidth || gz < 0 || gz >= gridDepth) return 0f
        return heights[gz * gridWidth + gx]
    }

    /**
     * Get the surface normal at a grid coordinate (for lighting/character selection).
     * Computed via central differences of neighboring heights.
     */
    fun normalAt(gx: Int, gz: Int): Vector3 {
        val hL = heightAt(gx - 1, gz)
        val hR = heightAt(gx + 1, gz)
        val hD = heightAt(gx, gz - 1)
        val hU = heightAt(gx, gz + 1)

        // Cell spacing in world units
        val dx = worldWidth / gridWidth
        val dz = worldDepth / gridDepth

        // Normal from central differences: (-dh/dx, 1, -dh/dz) normalized
        val nx = -(hR - hL) / (2f * dx)
        val nz = -(hU - hD) / (2f * dz)

        return Vector3(nx, 1f, nz).normalized()
    }

    /**
     * Convert a grid coordinate to world-space 3D position (including height as Y).
     */
    fun worldPosition(gx: Int, gz: Int): Vector3 {
        val wx = (gx.toFloat() / gridWidth) * worldWidth - worldWidth / 2f
        val wz = (gz.toFloat() / gridDepth) * worldDepth - worldDepth / 2f
        val wy = heightAt(gx, gz)
        return Vector3(wx, wy, wz)
    }

    private fun computeTargetHeights(
        substrate: SubstrateState,
        agents: AgentLayer,
        flow: FlowLayer?
    ) {
        // Step 1: Sample substrate density as base height
        for (gz in 0 until gridDepth) {
            for (gx in 0 until gridWidth) {
                // Map grid coordinates to substrate coordinates
                val sx = (gx.toFloat() / gridWidth * substrate.width).toInt()
                    .coerceIn(0, substrate.width - 1)
                val sy = (gz.toFloat() / gridDepth * substrate.height).toInt()
                    .coerceIn(0, substrate.height - 1)

                val density = substrate.getDensity(sx, sy)
                targetHeights[gz * gridWidth + gx] = density * baseHeightScale
            }
        }

        // Step 2: Add Gaussian peaks at agent positions
        for (agent in agents.allAgents) {
            addAgentPeak(agent)
        }

        // Step 3: Add ridges between connected agents
        if (flow != null) {
            addFlowRidges(agents, flow)
        }
    }

    private fun addAgentPeak(agent: AgentVisualState) {
        val activityHeight = when (agent.state) {
            AgentActivityState.IDLE -> 0.2f
            AgentActivityState.ACTIVE -> 0.6f
            AgentActivityState.PROCESSING -> 0.8f
            AgentActivityState.SPAWNING -> 1.0f
            AgentActivityState.COMPLETE -> 0.3f
        } * agentPeakScale

        val radius = defaultInfluenceRadius

        // Convert agent's 2D position to world-space fraction
        // Agent positions are in screen/grid coordinates; normalize them
        val agentWorldX = agent.position.x
        val agentWorldZ = agent.position.y

        for (gz in 0 until gridDepth) {
            for (gx in 0 until gridWidth) {
                val wx = (gx.toFloat() / gridWidth) * worldWidth
                val wz = (gz.toFloat() / gridDepth) * worldDepth

                val dx = wx - agentWorldX
                val dz = wz - agentWorldZ
                val distSq = dx * dx + dz * dz

                // Gaussian falloff: e^(-dist^2 / (2 * sigma^2))
                val sigma = radius * 0.5f
                val gaussian = exp(-distSq / (2f * sigma * sigma))

                targetHeights[gz * gridWidth + gx] += activityHeight * gaussian
            }
        }
    }

    private fun addFlowRidges(agents: AgentLayer, flow: FlowLayer) {
        for (connection in flow.allConnections) {
            if (!connection.isActive) continue

            val source = agents.getAgent(connection.sourceAgentId) ?: continue
            val target = agents.getAgent(connection.targetAgentId) ?: continue

            val sx = source.position.x
            val sz = source.position.y
            val tx = target.position.x
            val tz = target.position.y

            // Direction along the ridge
            val ridgeDx = tx - sx
            val ridgeDz = tz - sz
            val ridgeLen = sqrt(ridgeDx * ridgeDx + ridgeDz * ridgeDz)
            if (ridgeLen < 0.001f) continue

            val dirX = ridgeDx / ridgeLen
            val dirZ = ridgeDz / ridgeLen

            for (gz in 0 until gridDepth) {
                for (gx in 0 until gridWidth) {
                    val wx = (gx.toFloat() / gridWidth) * worldWidth
                    val wz = (gz.toFloat() / gridDepth) * worldDepth

                    // Vector from source to this point
                    val px = wx - sx
                    val pz = wz - sz

                    // Project onto ridge direction
                    val t = (px * dirX + pz * dirZ) / ridgeLen

                    // Only add ridge between source and target
                    if (t < 0f || t > 1f) continue

                    // Perpendicular distance from ridge line
                    val closestX = sx + ridgeDx * t
                    val closestZ = sz + ridgeDz * t
                    val perpDx = wx - closestX
                    val perpDz = wz - closestZ
                    val perpDist = sqrt(perpDx * perpDx + perpDz * perpDz)

                    // Gaussian falloff perpendicular to the ridge
                    val perpSigma = ridgeWidth * 0.5f
                    val perpGaussian = exp(-perpDist * perpDist / (2f * perpSigma * perpSigma))

                    // Height tapers at endpoints (sin curve for smooth blend)
                    val endpointFade = kotlin.math.sin(t * kotlin.math.PI.toFloat())

                    val ridgeHeight = ridgeScale * perpGaussian * endpointFade * connection.progress

                    targetHeights[gz * gridWidth + gx] += ridgeHeight
                }
            }
        }
    }
}
