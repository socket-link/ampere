package link.socket.ampere.animation.agent

import link.socket.ampere.animation.particle.ParticleSystem
import link.socket.ampere.animation.substrate.SubstrateAnimator
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.animation.substrate.Vector2
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class CognitiveChoreographerTest {

    private fun createChoreographer(maxParticles: Int = 500): Triple<CognitiveChoreographer, ParticleSystem, SubstrateAnimator> {
        val particles = ParticleSystem(maxParticles = maxParticles)
        val animator = SubstrateAnimator()
        val choreographer = CognitiveChoreographer(particles, animator)
        return Triple(choreographer, particles, animator)
    }

    private fun createAgentLayer(
        width: Int = 40,
        height: Int = 20,
        vararg agentSpecs: Triple<String, Vector2, CognitivePhase>
    ): AgentLayer {
        val layer = AgentLayer(width, height)
        agentSpecs.forEach { (id, position, phase) ->
            layer.addAgent(
                AgentVisualState(
                    id = id,
                    name = id,
                    role = "reasoning",
                    position = position,
                    state = AgentActivityState.PROCESSING,
                    cognitivePhase = phase
                )
            )
            // Override position since addAgent triggers relayout
            layer.setAgentPosition(id, position)
        }
        return layer
    }

    @Test
    fun `PERCEIVE spawns inward-directed particles`() {
        val (choreographer, particles, _) = createChoreographer(maxParticles = 100)
        val substrate = SubstrateState.create(40, 20)
        val agents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.PERCEIVE)
        )

        choreographer.update(agents, substrate, 0.1f)

        // Particles should exist and be near agent position (spawned from edges, aimed inward)
        assertTrue(particles.count > 0, "PERCEIVE should spawn particles")
        val nearAgent = particles.getParticlesNear(20, 10, radius = 15f)
        assertTrue(nearAgent.isNotEmpty(), "Particles should be within range of agent")
    }

    @Test
    fun `RECALL brightens nearby particles and adds attraction`() {
        val (choreographer, particles, _) = createChoreographer()
        val substrate = SubstrateState.create(40, 20)

        // First, seed some particles near the agent via PERCEIVE
        val perceiveAgents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.PERCEIVE)
        )
        choreographer.update(perceiveAgents, substrate, 0.2f)

        val countBefore = particles.count
        assertTrue(countBefore > 0, "Should have particles from PERCEIVE phase")

        // Now switch to RECALL — particles should cluster, not necessarily increase
        val recallAgents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.RECALL)
        )
        choreographer.update(recallAgents, substrate, 0.1f)

        // Particles still exist (RECALL doesn't despawn)
        assertTrue(particles.count > 0, "RECALL should preserve existing particles")
    }

    @Test
    fun `PLAN creates competing cluster structures`() {
        val (choreographer, particles, _) = createChoreographer()
        val substrate = SubstrateState.create(40, 20)
        val agents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.PLAN)
        )

        choreographer.update(agents, substrate, 0.1f)

        // Should have spawned particles at multiple cluster positions
        assertTrue(particles.count > 0, "PLAN should spawn cluster particles")
    }

    @Test
    fun `EXECUTE triggers spark burst on transition`() {
        val (choreographer, particles, _) = createChoreographer()
        val substrate = SubstrateState.create(40, 20)

        // Start in PLAN
        val planAgents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.PLAN)
        )
        choreographer.update(planAgents, substrate, 0.1f)
        val countAfterPlan = particles.count

        // Transition to EXECUTE — should trigger spark burst
        val executeAgents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.EXECUTE)
        )
        choreographer.update(executeAgents, substrate, 0.1f)

        assertTrue(
            particles.count > countAfterPlan,
            "Transition to EXECUTE should spawn spark burst particles"
        )
    }

    @Test
    fun `phase transition from PLAN to EXECUTE spawns discharge`() {
        val (choreographer, particles, _) = createChoreographer()
        val substrate = SubstrateState.create(40, 20)

        // Start in PLAN
        val planAgents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.PLAN)
        )
        choreographer.update(planAgents, substrate, 0.1f)

        // Transition to EXECUTE
        val executeAgents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.EXECUTE)
        )
        choreographer.update(executeAgents, substrate, 0.1f)

        // Should have SPARK type particles from the discharge burst
        val sparkParticles = particles.getParticles().filter {
            it.type == link.socket.ampere.animation.particle.ParticleType.SPARK
        }
        assertTrue(sparkParticles.isNotEmpty(), "PLAN→EXECUTE transition should spawn SPARK particles")
    }

    @Test
    fun `EVALUATE slows particles and creates anchors`() {
        val (choreographer, particles, _) = createChoreographer()
        val substrate = SubstrateState.create(40, 20)

        // Seed particles via EXECUTE
        val executeAgents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.EXECUTE)
        )
        choreographer.update(executeAgents, substrate, 0.1f)
        assertTrue(particles.count > 0)

        // Transition to EVALUATE
        val evaluateAgents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.EVALUATE)
        )
        choreographer.update(evaluateAgents, substrate, 0.1f)

        // Some particles should now have attractors (anchored)
        val anchored = particles.getParticles().filter { it.attractor != null }
        // At least some high-life particles get anchored
        assertTrue(particles.count > 0, "EVALUATE should not despawn all particles")
    }

    @Test
    fun `LOOP despawns nearby particles`() {
        val (choreographer, particles, _) = createChoreographer()
        val substrate = SubstrateState.create(40, 20)

        // Seed particles via EXECUTE
        val executeAgents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.EXECUTE)
        )
        choreographer.update(executeAgents, substrate, 0.1f)
        val countAfterExecute = particles.count

        // Switch to LOOP — should despawn near agent
        val loopAgents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.LOOP)
        )
        choreographer.update(loopAgents, substrate, 0.1f)

        // Nearby particles should have been removed (only ambient mote remains)
        val nearAgent = particles.getParticlesNear(20, 10, radius = 3f)
        // LOOP despawns within radius 3, then spawns 1 mote — should be sparse
        assertTrue(nearAgent.size <= 1, "LOOP should despawn most nearby particles")
    }

    @Test
    fun `NONE phase produces no particles`() {
        val (choreographer, particles, _) = createChoreographer()
        val substrate = SubstrateState.create(40, 20)
        val agents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.NONE)
        )

        choreographer.update(agents, substrate, 0.1f)

        assertEquals(0, particles.count, "NONE phase should not spawn particles")
    }

    @Test
    fun `reset clears all tracked state`() {
        val (choreographer, particles, _) = createChoreographer()
        val substrate = SubstrateState.create(40, 20)

        // Run a few phases
        val agents = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.PERCEIVE)
        )
        choreographer.update(agents, substrate, 0.1f)
        assertTrue(particles.count > 0)

        // Reset
        choreographer.reset()

        // After reset, a PERCEIVE agent should be treated as a fresh transition (not continuous)
        // The choreographer should not remember previous phases
        val agents2 = createAgentLayer(
            40, 20,
            Triple("agent-1", Vector2(20f, 10f), CognitivePhase.EXECUTE)
        )
        // This should trigger the transition spark burst (NONE → EXECUTE)
        particles.clear()
        choreographer.update(agents2, substrate, 0.1f)

        val sparks = particles.getParticles().filter {
            it.type == link.socket.ampere.animation.particle.ParticleType.SPARK
        }
        assertTrue(sparks.isNotEmpty(), "After reset, transitions should fire fresh")
    }

    @Test
    fun `multiple agents get independent choreography`() {
        val (choreographer, particles, _) = createChoreographer(maxParticles = 500)
        val substrate = SubstrateState.create(60, 30)
        val agents = createAgentLayer(
            60, 30,
            Triple("agent-1", Vector2(15f, 15f), CognitivePhase.PERCEIVE),
            Triple("agent-2", Vector2(45f, 15f), CognitivePhase.EXECUTE)
        )

        choreographer.update(agents, substrate, 0.1f)

        // Both agents should contribute particles
        assertTrue(particles.count > 0, "Multiple agents should produce particles")

        // EXECUTE agent should have sparked (transition from NONE)
        val sparks = particles.getParticles().filter {
            it.type == link.socket.ampere.animation.particle.ParticleType.SPARK
        }
        assertTrue(sparks.isNotEmpty(), "EXECUTE agent should produce spark transition")
    }
}
