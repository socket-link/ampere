package link.socket.ampere.animation.emitter

import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.math.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CognitiveEmitterBridgeTest {

    private fun createBridge(): Pair<EmitterManager, CognitiveEmitterBridge> {
        val manager = EmitterManager()
        val bridge = CognitiveEmitterBridge(manager)
        return manager to bridge
    }

    private val agentPos = Vector3(3f, 0f, 2f)

    // --- SparkReceived ---

    @Test
    fun `SparkReceived fires both SparkBurst and HeightPulse`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(CognitiveEvent.SparkReceived("agent-1"), agentPos)
        assertEquals(2, manager.activeCount)
        val names = manager.instances.map { it.effect.name }.toSet()
        assertTrue("spark_burst" in names)
        assertTrue("height_pulse" in names)
    }

    @Test
    fun `SparkReceived effects are positioned at agent location`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(CognitiveEvent.SparkReceived("agent-1"), agentPos)
        for (instance in manager.instances) {
            assertEquals(agentPos, instance.position)
        }
    }

    // --- PhaseTransition ---

    @Test
    fun `PhaseTransition fires a ColorWash`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(
            CognitiveEvent.PhaseTransition("agent-1", CognitivePhase.PERCEIVE, CognitivePhase.RECALL),
            agentPos
        )
        assertEquals(1, manager.activeCount)
        assertEquals("color_wash", manager.instances.first().effect.name)
    }

    @Test
    fun `PhaseTransition to EXECUTE uses EXECUTE color ramp`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(
            CognitiveEvent.PhaseTransition("agent-1", CognitivePhase.PLAN, CognitivePhase.EXECUTE),
            agentPos
        )
        val wash = manager.instances.first().effect as EmitterEffect.ColorWash
        assertEquals(CognitivePhase.EXECUTE, wash.colorRamp.phase)
    }

    @Test
    fun `PhaseTransition to PERCEIVE uses PERCEIVE color ramp`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(
            CognitiveEvent.PhaseTransition("agent-1", CognitivePhase.NONE, CognitivePhase.PERCEIVE),
            agentPos
        )
        val wash = manager.instances.first().effect as EmitterEffect.ColorWash
        assertEquals(CognitivePhase.PERCEIVE, wash.colorRamp.phase)
    }

    // --- UncertaintySpike ---

    @Test
    fun `UncertaintySpike fires Turbulence`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(CognitiveEvent.UncertaintySpike("agent-1", 0.8f), agentPos)
        assertEquals(1, manager.activeCount)
        assertEquals("turbulence", manager.instances.first().effect.name)
    }

    @Test
    fun `UncertaintySpike scales noise amplitude with level`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(CognitiveEvent.UncertaintySpike("agent-1", 0.5f), agentPos)
        val turb = manager.instances.first().effect as EmitterEffect.Turbulence
        assertEquals(0.75f, turb.noiseAmplitude, 0.01f)
    }

    @Test
    fun `UncertaintySpike clamps level to 0-1`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(CognitiveEvent.UncertaintySpike("agent-1", 2f), agentPos)
        val turb = manager.instances.first().effect as EmitterEffect.Turbulence
        // 1.5f * coerceIn(2f, 0f, 1f) = 1.5f * 1f = 1.5f
        assertEquals(1.5f, turb.noiseAmplitude, 0.01f)
    }

    // --- TaskCompleted ---

    @Test
    fun `TaskCompleted fires Confetti`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(CognitiveEvent.TaskCompleted("agent-1"), agentPos)
        assertEquals(1, manager.activeCount)
        assertEquals("confetti", manager.instances.first().effect.name)
    }

    // --- HumanEscalation ---

    @Test
    fun `HumanEscalation fires large SparkBurst`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(CognitiveEvent.HumanEscalation("agent-1"), agentPos)
        assertEquals(1, manager.activeCount)
        val burst = manager.instances.first().effect as EmitterEffect.SparkBurst
        assertEquals("spark_burst", burst.name)
        assertEquals(2f, burst.duration)
        assertEquals(10f, burst.radius)
    }

    // --- Multiple events ---

    @Test
    fun `multiple events from different agents accumulate effects`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(CognitiveEvent.SparkReceived("agent-1"), Vector3.ZERO)
        bridge.onCognitiveEvent(CognitiveEvent.TaskCompleted("agent-2"), Vector3(5f, 0f, 5f))
        // SparkReceived = 2 effects + TaskCompleted = 1 effect
        assertEquals(3, manager.activeCount)
    }

    @Test
    fun `effects expire after update passes their duration`() {
        val (manager, bridge) = createBridge()
        bridge.onCognitiveEvent(CognitiveEvent.TaskCompleted("agent-1"), agentPos)
        assertEquals(1, manager.activeCount)
        // Confetti default duration is 1.0s
        manager.update(1.1f)
        assertEquals(0, manager.activeCount)
    }

    // --- CognitiveEvent data ---

    @Test
    fun `CognitiveEvent subclasses carry correct data`() {
        val spark = CognitiveEvent.SparkReceived("a1")
        assertEquals("a1", spark.agentId)

        val phase = CognitiveEvent.PhaseTransition("a2", CognitivePhase.PLAN, CognitivePhase.EXECUTE)
        assertEquals("a2", phase.agentId)
        assertEquals(CognitivePhase.PLAN, phase.oldPhase)
        assertEquals(CognitivePhase.EXECUTE, phase.newPhase)

        val uncertainty = CognitiveEvent.UncertaintySpike("a3", 0.7f)
        assertEquals("a3", uncertainty.agentId)
        assertEquals(0.7f, uncertainty.level)

        val completed = CognitiveEvent.TaskCompleted("a4")
        assertEquals("a4", completed.agentId)

        val escalation = CognitiveEvent.HumanEscalation("a5")
        assertEquals("a5", escalation.agentId)
    }
}
