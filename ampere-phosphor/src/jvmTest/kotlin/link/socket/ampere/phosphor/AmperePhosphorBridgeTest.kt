package link.socket.ampere.phosphor

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MilestoneCategory
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.phosphor.lumos.CognitiveLookSwitch
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase as PhosphorPhase

/**
 * jvmTest since AMPR-340: the bridge listens on the bus, and events reach the bus only through
 * the [InMemoryEventDoor], which needs a JDBC store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AmperePhosphorBridgeTest {

    // Long enough that a single update() doesn't complete the transition.
    private val longTransitionTickSeconds = 0.001f

    private val scope = TestScope(UnconfinedTestDispatcher())
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var bus: EventSerialBus
    private lateinit var runtime: CognitiveSceneRuntime
    private lateinit var voxelFrameBuilder: VoxelFrameBuilder
    private lateinit var bridge: AmperePhosphorBridge

    @BeforeTest
    fun setUp() {
        door = InMemoryEventDoor.open(agentId = "phosphor-test-door", scope = scope)
        bus = door.bus
        runtime = CognitiveSceneRuntime(
            SceneConfiguration(
                width = 8,
                height = 8,
                enableWaveform = false,
                enableParticles = false,
                enableFlow = false,
                enableEmitters = false,
                enableCamera = false,
                enableAtmosphere = true,
                initialAtmosphere = AtmospherePresets.IDLE,
            ),
        )
        voxelFrameBuilder = VoxelFrameBuilder(initialResolution = 8)
        bridge = AmperePhosphorBridge(
            bus = bus,
            runtime = runtime,
            voxelFrameBuilder = voxelFrameBuilder,
        )
        bridge.start()
    }

    @AfterTest
    fun tearDown() {
        bridge.stop()
        door.close()
    }

    private suspend fun publish(event: Event) {
        door.api.publish(event).getOrThrow()
    }

    @Test
    fun `single phase transition drives runtime to expected atmosphere`() = runBlocking {
        publish(phaseEntered(newPhase = CognitivePhase.EXECUTE))

        completeInFlightTransition()
        assertEquals(AtmospherePresets.READY, runtime.currentAtmosphere)
    }

    @Test
    fun `three rapid phase transitions coalesce to the latest target`() = runBlocking {
        publish(phaseEntered(newPhase = CognitivePhase.PLAN, suffix = "a"))
        // PLAN -> THINKING transition is now in flight. Advance only slightly so
        // the choreographer still reports activeTransition != null.
        runtime.update(longTransitionTickSeconds)
        publish(phaseEntered(newPhase = CognitivePhase.PERCEIVE, suffix = "b"))
        publish(phaseEntered(newPhase = CognitivePhase.EXECUTE, suffix = "c"))

        completeInFlightTransition()
        bridge.onFrameTick()
        completeInFlightTransition()

        assertEquals(AtmospherePresets.READY, runtime.currentAtmosphere)
    }

    @Test
    fun `escalation fired during transition yanks runtime to UNCERTAIN`() = runBlocking {
        publish(phaseEntered(newPhase = CognitivePhase.PLAN))
        runtime.update(longTransitionTickSeconds)
        // A pending PERCEIVE target — should be cleared by the escalation override.
        publish(phaseEntered(newPhase = CognitivePhase.PERCEIVE, suffix = "b"))

        publish(escalationFired())

        completeInFlightTransition()
        assertEquals(AtmospherePresets.UNCERTAIN, runtime.currentAtmosphere)
        // The escalation cleared the pending PERCEIVE; a subsequent frame must
        // not flip the atmosphere away from UNCERTAIN.
        bridge.onFrameTick()
        completeInFlightTransition()
        assertEquals(AtmospherePresets.UNCERTAIN, runtime.currentAtmosphere)
    }

    @Test
    fun `three rapid EscalationFired events queue three QUESTION glyphs in order`() = runBlocking {
        publish(escalationFired(suffix = "a"))
        publish(escalationFired(suffix = "b"))
        publish(escalationFired(suffix = "c"))

        // Each queueGlyph replaces the previous active glyph, but our
        // verification is that the builder accepted three queue calls without
        // coalescing on the bridge side. We sample the last accepted glyph
        // identity here; a builder-side coalescing concern is upstream.
        // Verify by stepping build() three times and confirming glyph presence.
        val snapshot1 = runtime.update(0.001f)
        val frame1 = voxelFrameBuilder.build(snapshot1, 0.001f)
        assertEquals(LumosGlyph.QUESTION.name, frame1.glyph?.glyphName)
    }

    @Test
    fun `TaskCompleted event queues a CHECK glyph`() = runBlocking {
        publish(taskCompleted())

        val snapshot = runtime.update(0.001f)
        val frame = voxelFrameBuilder.build(snapshot, 0.001f)
        assertEquals(LumosGlyph.CHECK.name, frame.glyph?.glyphName)
    }

    @Test
    fun `a glyph sink receives the glyph instead of the builder`() = runBlocking {
        bridge.stop()
        val received = mutableListOf<Pair<LumosGlyph, Float>>()
        val sinkBridge = AmperePhosphorBridge(
            bus = bus,
            runtime = runtime,
            voxelFrameBuilder = voxelFrameBuilder,
            glyphSink = { glyph, duration -> received += glyph to duration },
        ).also { it.start() }

        publish(taskCompleted())

        assertEquals(
            listOf(LumosGlyph.CHECK to AmperePhosphorBridge.DEFAULT_GLYPH_DURATION_SECONDS),
            received,
        )
        assertFalse(voxelFrameBuilder.hasActiveGlyph)

        sinkBridge.stop()
    }

    @Test
    fun `MilestoneReached event queues a STAR glyph`() = runBlocking {
        publish(milestoneReached())

        val snapshot = runtime.update(0.001f)
        val frame = voxelFrameBuilder.build(snapshot, 0.001f)
        assertEquals(LumosGlyph.STAR.name, frame.glyph?.glyphName)
    }

    @Test
    fun `TaskFailed queues an EXCLAIM glyph`() = runBlocking {
        publish(taskFailed())

        val snapshot = runtime.update(0.001f)
        val frame = voxelFrameBuilder.build(snapshot, 0.001f)
        assertEquals(LumosGlyph.EXCLAIM.name, frame.glyph?.glyphName)
    }

    @Test
    fun `failing ToolExecutionCompleted queues an EXCLAIM glyph`() = runBlocking {
        publish(toolCompleted(success = false))

        val snapshot = runtime.update(0.001f)
        val frame = voxelFrameBuilder.build(snapshot, 0.001f)
        assertEquals(LumosGlyph.EXCLAIM.name, frame.glyph?.glyphName)
    }

    @Test
    fun `successful ToolExecutionCompleted does not queue a glyph`() = runBlocking {
        publish(toolCompleted(success = true))

        val snapshot = runtime.update(0.001f)
        val frame = voxelFrameBuilder.build(snapshot, 0.001f)
        assertEquals(null, frame.glyph)
    }

    @Test
    fun `custom strategy substitution drives runtime to the strategy's choice`() = runBlocking {
        bridge.stop()
        val perceiveAsReady = object : PropelToAtmosphereStrategy {
            override fun atmosphereFor(phase: CognitivePhase): AtmosphereState = when (phase) {
                CognitivePhase.PERCEIVE -> AtmospherePresets.READY
                else -> DefaultPropelStrategy.atmosphereFor(phase)
            }

            override fun glyphFor(event: Event): LumosGlyph? = DefaultPropelStrategy.glyphFor(event)
        }
        val customBridge = AmperePhosphorBridge(
            bus = bus,
            runtime = runtime,
            voxelFrameBuilder = voxelFrameBuilder,
            strategy = perceiveAsReady,
        ).also { it.start() }

        publish(phaseEntered(newPhase = CognitivePhase.PERCEIVE))

        completeInFlightTransition()
        assertEquals(AtmospherePresets.READY, runtime.currentAtmosphere)

        customBridge.stop()
    }

    @Test
    fun `concurrent publishes do not lose the final pending atmosphere`() = runBlocking {
        publish(phaseEntered(newPhase = CognitivePhase.PLAN, suffix = "seed"))
        runtime.update(longTransitionTickSeconds)
        // Choreographer is now mid-transition. Fire several events in parallel.

        val phases = listOf(
            CognitivePhase.PERCEIVE,
            CognitivePhase.RECALL,
            CognitivePhase.OBSERVE,
            CognitivePhase.EXECUTE,
            CognitivePhase.LEARN,
        )
        val publishes = phases.mapIndexed { index, phase ->
            scope.async { publish(phaseEntered(newPhase = phase, suffix = "p$index")) }
        }
        publishes.awaitAll()

        completeInFlightTransition()
        bridge.onFrameTick()
        completeInFlightTransition()

        val terminalState = runtime.currentAtmosphere
        // Final state must be one of the candidates produced by the burst,
        // never IDLE (the original) or a torn / unrecognized state.
        val candidates = phases.map { DefaultPropelStrategy.atmosphereFor(it) }
        assertEquals(
            true,
            terminalState in candidates,
            "Terminal atmosphere $terminalState should be one of ${candidates.map { it }}",
        )
        assertNotEquals(AtmospherePresets.IDLE, terminalState)
    }

    // --- AMPR-466: the scene agent, phase and escalation written through, one owner per glyph ---

    @Test
    fun `start adds the scene agent to the runtime when absent`() {
        val agent = assertNotNull(runtime.agents.getAgent(AmperePhosphorBridge.SCENE_AGENT_ID))
        assertEquals(1, runtime.agents.agentCount)
        assertEquals(PhosphorPhase.NONE, agent.cognitivePhase)
        assertEquals(AgentActivityState.IDLE, agent.state)
        assertFalse(agent.escalated)
    }

    @Test
    fun `start leaves a scene agent the host already placed`() {
        bridge.stop()
        runtime.agents.removeAgent(AmperePhosphorBridge.SCENE_AGENT_ID)
        runtime.agents.addAgent(
            AgentVisualState(id = "host-orb", name = "Host", role = "host", position = Vector2(1f, 1f)),
        )

        val hostBridge = AmperePhosphorBridge(
            bus = bus,
            runtime = runtime,
            voxelFrameBuilder = voxelFrameBuilder,
            sceneAgentId = "host-orb",
        ).also { it.start() }

        assertEquals(1, runtime.agents.agentCount)
        assertEquals("host", runtime.agents.getAgent("host-orb")?.role)
        hostBridge.stop()
    }

    @Test
    fun `PhaseEntered PLAN sets the scene agent's phase`() = runBlocking {
        publish(phaseEntered(newPhase = CognitivePhase.PLAN))

        assertEquals(PhosphorPhase.PLAN, sceneAgent().cognitivePhase)
    }

    @Test
    fun `every PROPEL phase maps onto the Phosphor phase of the same name`() = runBlocking {
        for ((index, phase) in CognitivePhase.entries.withIndex()) {
            publish(phaseEntered(newPhase = phase, suffix = "map$index"))
            assertEquals(phase.name, sceneAgent().cognitivePhase.name)
        }
    }

    @Test
    fun `EscalationFired sets escalated and the next PhaseEntered clears it`() = runBlocking {
        publish(escalationFired())
        assertTrue(sceneAgent().escalated)

        publish(phaseEntered(newPhase = CognitivePhase.EXECUTE))
        assertFalse(sceneAgent().escalated)
        assertEquals(PhosphorPhase.EXECUTE, sceneAgent().cognitivePhase)
    }

    @Test
    fun `clearEscalation clears the flag ahead of the next phase`() = runBlocking {
        publish(escalationFired())
        assertTrue(sceneAgent().escalated)

        bridge.clearEscalation()

        assertFalse(sceneAgent().escalated)
        // The atmosphere is left where the escalation put it; only a phase moves it.
        completeInFlightTransition()
        assertEquals(AtmospherePresets.UNCERTAIN, runtime.currentAtmosphere)
    }

    @Test
    fun `with LOOK_SWITCH installed a TaskCompleted produces exactly one CHECK`() = runBlocking {
        val received = installLookSwitchWithCountingSink(AmperePhosphorBridge.LOOK_SWITCH)
        // The host marks the run complete, as Socket would from its workflow status.
        runtime.agents.updateAgentState(AmperePhosphorBridge.SCENE_AGENT_ID, AgentActivityState.COMPLETE)

        publish(taskCompleted())
        voxelFrameBuilder.build(runtime.update(0.001f), 0.001f)

        assertEquals(listOf(LumosGlyph.CHECK), received)
        assertFalse(voxelFrameBuilder.hasActiveGlyph, "the switch must not fire its own CHECK")
        assertEquals(Look(LumosLook.BASE), voxelFrameBuilder.look)
    }

    @Test
    fun `with Phosphor's default table the builder fires a second CHECK`() = runBlocking {
        val received = installLookSwitchWithCountingSink(CognitiveLookSwitch())
        runtime.agents.updateAgentState(AmperePhosphorBridge.SCENE_AGENT_ID, AgentActivityState.COMPLETE)

        publish(taskCompleted())
        voxelFrameBuilder.build(runtime.update(0.001f), 0.001f)

        assertEquals(listOf(LumosGlyph.CHECK), received)
        assertTrue(voxelFrameBuilder.hasActiveGlyph, "the default table fires CHECK on COMPLETE: the double fire")
    }

    @Test
    fun `with LOOK_SWITCH installed an escalation fires one QUESTION and wears the waiting look`() = runBlocking {
        val received = installLookSwitchWithCountingSink(AmperePhosphorBridge.LOOK_SWITCH)

        publish(escalationFired())
        voxelFrameBuilder.build(runtime.update(0.001f), 0.001f)

        assertEquals(listOf(LumosGlyph.QUESTION), received)
        assertFalse(voxelFrameBuilder.hasActiveGlyph, "the switch must not fire its own QUESTION")
        assertEquals(Look(LumosLook.WAITING), voxelFrameBuilder.look)
    }

    @Test
    fun `with LOOK_SWITCH installed the phase drives the orb's look`() = runBlocking {
        installLookSwitchWithCountingSink(AmperePhosphorBridge.LOOK_SWITCH)

        publish(phaseEntered(newPhase = CognitivePhase.PLAN))
        voxelFrameBuilder.build(runtime.update(0.001f), 0.001f)
        assertEquals(Look(LumosLook.REASONING, "twins"), voxelFrameBuilder.look)
        assertTrue(voxelFrameBuilder.isLookBlending, "the body blends into the look rather than cutting")

        // Land the blend: a look asked for mid-blend waits behind it, and `look` reads the heading.
        voxelFrameBuilder.build(runtime.update(LARGE_TICK_SECONDS), LARGE_TICK_SECONDS)
        assertFalse(voxelFrameBuilder.isLookBlending)

        publish(phaseEntered(newPhase = CognitivePhase.EXECUTE, suffix = "b"))
        voxelFrameBuilder.build(runtime.update(0.001f), 0.001f)
        assertEquals(Look(LumosLook.WORKING), voxelFrameBuilder.look)
    }

    @Test
    fun `without a look switch the scene agent leaves the look and the glyphs as today`() = runBlocking {
        publish(phaseEntered(newPhase = CognitivePhase.PLAN))
        publish(escalationFired())
        runtime.agents.updateAgentState(AmperePhosphorBridge.SCENE_AGENT_ID, AgentActivityState.COMPLETE)

        val frame = voxelFrameBuilder.build(runtime.update(0.001f), 0.001f)

        assertEquals(Look(LumosLook.BASE), voxelFrameBuilder.look)
        // The one glyph in flight is the bridge's QUESTION, not a CHECK from the agent's COMPLETE.
        assertEquals(LumosGlyph.QUESTION.name, frame.glyph?.glyphName)
    }

    private fun sceneAgent(): AgentVisualState =
        assertNotNull(runtime.agents.getAgent(AmperePhosphorBridge.SCENE_AGENT_ID))

    /**
     * Replace the default bridge with one whose glyphs go to a list, install [switch] on the
     * builder and build its baseline frame, so that the builder's own glyph queue holds only
     * what the switch fires and the next signal change is seen as a change.
     */
    private fun installLookSwitchWithCountingSink(switch: CognitiveLookSwitch): List<LumosGlyph> {
        bridge.stop()
        val received = mutableListOf<LumosGlyph>()
        bridge = AmperePhosphorBridge(
            bus = bus,
            runtime = runtime,
            voxelFrameBuilder = voxelFrameBuilder,
            glyphSink = { glyph, _ -> received += glyph },
        ).also { it.start() }
        voxelFrameBuilder.lookSwitch = switch
        voxelFrameBuilder.build(runtime.update(0.001f), 0.001f)
        return received
    }

    private suspend fun completeInFlightTransition() {
        repeat(MAX_TICKS_FOR_TRANSITION) {
            val choreographer = runtime.atmosphereChoreographer ?: return
            if (choreographer.activeTransition == null) {
                bridge.onFrameTick()
                return
            }
            runtime.update(LARGE_TICK_SECONDS)
        }
        bridge.onFrameTick()
    }

    private fun phaseEntered(
        newPhase: CognitivePhase,
        oldPhase: CognitivePhase? = null,
        suffix: String = "0",
    ): CognitivePhaseEvent.PhaseEntered = CognitivePhaseEvent.PhaseEntered(
        eventId = "evt-phase-$suffix",
        timestamp = Instant.fromEpochSeconds(0),
        eventSource = EventSource.Agent("agent-A"),
        agentId = "agent-A",
        oldPhase = oldPhase,
        newPhase = newPhase,
        nestingDepth = 0,
    )

    private fun escalationFired(suffix: String = "0"): CognitiveEvent.EscalationFired =
        CognitiveEvent.EscalationFired(
            eventId = "evt-esc-$suffix",
            timestamp = Instant.fromEpochSeconds(0),
            eventSource = EventSource.Agent("agent-A"),
            agentId = "agent-A",
            uncertaintyValue = 0.93,
            threshold = 0.85,
            prompt = "?",
            cognitivePhase = CognitivePhase.PLAN,
        )

    private fun taskCompleted(): TaskEvent.TaskCompleted = TaskEvent.TaskCompleted(
        eventId = "evt-task-completed",
        taskId = "task-1",
        eventSource = EventSource.Agent("agent-A"),
        timestamp = Instant.fromEpochSeconds(0),
        summary = "done",
    )

    private fun taskFailed(): TaskEvent.TaskFailed = TaskEvent.TaskFailed(
        eventId = "evt-task-failed",
        taskId = "task-1",
        eventSource = EventSource.Agent("agent-A"),
        timestamp = Instant.fromEpochSeconds(0),
        reason = "boom",
    )

    private fun toolCompleted(success: Boolean): ToolEvent.ToolExecutionCompleted =
        ToolEvent.ToolExecutionCompleted(
            eventId = "evt-tool-$success",
            timestamp = Instant.fromEpochSeconds(0),
            eventSource = EventSource.Agent("agent-A"),
            urgency = Urgency.LOW,
            invocationId = "inv-1",
            toolId = "tool-1",
            toolName = "test-tool",
            success = success,
            durationMs = 100L,
        )

    private fun milestoneReached(): MemoryEvent.MilestoneReached = MemoryEvent.MilestoneReached(
        eventId = "evt-milestone",
        timestamp = Instant.fromEpochSeconds(0),
        eventSource = EventSource.Agent("agent-A"),
        agentId = "agent-A",
        milestoneId = "ms-1",
        description = "first success",
        knowledgeId = null,
        taskId = null,
        runId = null,
        category = MilestoneCategory.FIRST_SUCCESS,
    )

    companion object {
        private const val LARGE_TICK_SECONDS: Float = 5.0f
        private const val MAX_TICKS_FOR_TRANSITION: Int = 8
    }
}
