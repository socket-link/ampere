package link.socket.ampere.phosphor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.phosphor.lumos.CognitiveLookSwitch
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase as PhosphorPhase

/**
 * Bridges AMPERE's [EventSerialBus] onto a Phosphor Lumos scene.
 *
 * The bridge translates each [CognitivePhaseEvent.PhaseEntered] into an
 * [AtmosphereState] target via [strategy]. Targets are coalesced: when a phase
 * event arrives while [CognitiveSceneRuntime.atmosphereChoreographer] is
 * mid-transition, the pending target is replaced rather than appended.
 *
 * `EscalationFired` events bypass the queue and immediately drive the runtime
 * to [AtmospherePresets.UNCERTAIN], interrupting any transition in flight.
 *
 * Glyphs are not coalesced — every event that resolves to a non-null
 * [LumosGlyph] is handed to [glyphSink], which by default queues it on
 * [voxelFrameBuilder]. That queue only drains inside [VoxelFrameBuilder.build],
 * so a consumer that never calls `build()` — one replaying a recorded trace
 * rather than rendering live frames — passes its own sink and overlays the
 * glyph on the replayed frame itself.
 *
 * ### The scene agent (AMPR-466)
 *
 * The atmosphere is the orb's skin. Its body is driven by Phosphor's
 * [CognitiveLookSwitch], which reads a scene agent's `cognitivePhase`, `state`
 * and `escalated` from each snapshot once a host installs a switch as
 * [VoxelFrameBuilder.lookSwitch]. So the bridge keeps one scene agent for the
 * whole bus — [sceneAgentId], added to [CognitiveSceneRuntime.agents] on
 * [start] if it is absent, and left alone if the host already placed it — and
 * writes through to it:
 *
 * - `PhaseEntered` sets the agent's cognitive phase (the six PROPEL names map
 *   one to one onto Phosphor's; AMPERE has no `LOOP` or `NONE`) and clears
 *   escalation.
 * - `EscalationFired` raises escalation. The bus carries no end to an
 *   escalation, so the flag holds until the next `PhaseEntered`, or until a
 *   host that knows the person answered calls [clearEscalation].
 *
 * The bridge never touches the agent's activity `state`: a run's lifecycle
 * lives with the host, which may set it from its own workflow status.
 *
 * A host that installs no switch sees no change: the builder ignores agents
 * while `lookSwitch` is null, so the atmosphere path and the glyphs are exactly
 * as before the agent existed.
 *
 * ### One owner per glyph
 *
 * The bridge owns every glyph. It sees the events — including per-task
 * completions that agent state never carries — so it fires CHECK on
 * `TaskCompleted` and QUESTION on `EscalationFired` through [glyphSink].
 * Phosphor's default look table fires those same two glyphs itself, on
 * `COMPLETE` activity and on escalation, and `queueGlyph` restarts the fade,
 * so a host installing the default table next to this bridge would see each
 * glyph fire twice and blink. Install [LOOK_SWITCH] instead: it is Phosphor's
 * default table with every glyph dropped, so the switch moves the body and
 * the bridge alone fires the glyphs.
 *
 * The bridge does not own a frame loop. The consumer must call [onFrameTick]
 * once per frame after [CognitiveSceneRuntime.update] so that pending
 * atmosphere targets can be flushed when the in-flight transition completes.
 *
 * @property sceneAgentId Id of the one scene agent the bridge writes phase and
 *  escalation into. Defaults to [SCENE_AGENT_ID]. Distinct from [agentId], which
 *  is the bridge's subscriber id on the bus.
 */
class AmperePhosphorBridge(
    private val bus: EventSerialBus,
    private val runtime: CognitiveSceneRuntime,
    private val voxelFrameBuilder: VoxelFrameBuilder,
    private val strategy: PropelToAtmosphereStrategy = DefaultPropelStrategy,
    private val agentId: String = BRIDGE_AGENT_ID,
    private val glyphDurationSeconds: Float = DEFAULT_GLYPH_DURATION_SECONDS,
    private val glyphSink: (glyph: LumosGlyph, durationSeconds: Float) -> Unit =
        { glyph, duration -> voxelFrameBuilder.queueGlyph(glyph, duration) },
    val sceneAgentId: String = SCENE_AGENT_ID,
) {
    private val mutex = Mutex()
    private var pendingAtmosphere: AtmosphereState? = null
    private val subscriptions: MutableList<Subscription> = mutableListOf()
    private var started: Boolean = false

    /**
     * Register handlers on the bus, and add the scene agent to
     * [CognitiveSceneRuntime.agents] if the host has not placed one under
     * [sceneAgentId]. Subsequent calls while started are no-ops.
     */
    fun start() {
        if (started) return
        started = true

        ensureSceneAgent()

        register(CognitivePhaseEvent.PhaseEntered.EVENT_TYPE) { event ->
            if (event is CognitivePhaseEvent.PhaseEntered) handlePhaseEntered(event)
        }
        register(CognitiveEvent.EscalationFired.EVENT_TYPE) { event ->
            if (event is CognitiveEvent.EscalationFired) {
                handleEscalationFired(event)
                handleGlyphSource(event)
            }
        }
        register(TaskEvent.TaskCompleted.EVENT_TYPE) { event -> handleGlyphSource(event) }
        register(TaskEvent.TaskFailed.EVENT_TYPE) { event -> handleGlyphSource(event) }
        register(ToolEvent.ToolExecutionCompleted.EVENT_TYPE) { event -> handleGlyphSource(event) }
        register(MemoryEvent.MilestoneReached.EVENT_TYPE) { event -> handleGlyphSource(event) }
    }

    /**
     * Unsubscribe the bridge's handlers from the bus.
     *
     * Released per subscription, so other subscribers on the same event types keep
     * receiving events after the bridge stops. The scene agent stays in the
     * runtime: the host that owns the scene decides when it goes.
     */
    fun stop() {
        if (!started) return
        started = false
        for (subscription in subscriptions) {
            bus.unsubscribe(subscription)
        }
        subscriptions.clear()
    }

    /**
     * Clear the scene agent's escalation flag now, for a host that knows the
     * person has answered. The next `PhaseEntered` clears it anyway; this only
     * brings that forward. The atmosphere is left where it is — a phase event
     * moves it, as today.
     */
    fun clearEscalation() {
        runtime.agents.updateAgentEscalated(sceneAgentId, false)
    }

    private fun register(eventType: EventType, dispatch: suspend (Event) -> Unit) {
        subscriptions += bus.subscribe(
            agentId = agentId,
            eventType = eventType,
            handler = EventHandler<Event, Subscription> { event, _ ->
                if (started) dispatch(event)
            },
        )
    }

    private fun ensureSceneAgent() {
        if (runtime.agents.getAgent(sceneAgentId) != null) return
        runtime.agents.addAgent(
            AgentVisualState(
                id = sceneAgentId,
                name = sceneAgentId,
                role = SCENE_AGENT_ROLE,
                position = Vector2(0f, 0f),
            ),
        )
    }

    /**
     * Apply the pending atmosphere target if the choreographer's previous
     * transition has completed. Should be called once per frame from the
     * consumer's frame loop.
     */
    suspend fun onFrameTick() {
        val choreographer = runtime.atmosphereChoreographer ?: return
        if (choreographer.activeTransition != null) return
        val next = mutex.withLock {
            val pending = pendingAtmosphere
            pendingAtmosphere = null
            pending
        } ?: return
        runtime.setAtmosphere(next)
    }

    private suspend fun handlePhaseEntered(event: CognitivePhaseEvent.PhaseEntered) {
        runtime.agents.updateAgentCognitivePhase(sceneAgentId, event.newPhase.toPhosphorPhase())
        runtime.agents.updateAgentEscalated(sceneAgentId, false)

        val target = strategy.atmosphereFor(event.newPhase)
        val choreographer = runtime.atmosphereChoreographer
        if (choreographer == null || choreographer.activeTransition == null) {
            mutex.withLock { pendingAtmosphere = null }
            runtime.setAtmosphere(target)
        } else {
            mutex.withLock { pendingAtmosphere = target }
        }
    }

    private suspend fun handleEscalationFired(@Suppress("UNUSED_PARAMETER") event: CognitiveEvent.EscalationFired) {
        runtime.agents.updateAgentEscalated(sceneAgentId, true)

        mutex.withLock { pendingAtmosphere = null }
        runtime.setAtmosphere(AtmospherePresets.UNCERTAIN)
    }

    private fun handleGlyphSource(event: Event) {
        val glyph = strategy.glyphFor(event) ?: return
        glyphSink(glyph, glyphDurationSeconds)
    }

    companion object {
        const val BRIDGE_AGENT_ID: String = "ampere-phosphor-bridge"
        const val DEFAULT_GLYPH_DURATION_SECONDS: Float = 1.5f

        /** Default id of the one scene agent the bridge drives; see [sceneAgentId]. */
        const val SCENE_AGENT_ID: String = "ampere"

        /** Role the bridge gives the scene agent it adds. Display-only in Phosphor. */
        const val SCENE_AGENT_ROLE: String = "ampere"

        /**
         * Phosphor's default phase → look table with every glyph dropped, for a
         * host to install as [VoxelFrameBuilder.lookSwitch] next to this bridge.
         *
         * The switch then drives the orb's body — searching lens for PERCEIVE,
         * reasoning walk for RECALL, lighthouse beam for OBSERVE, twin walkers for
         * PLAN, working ring for EXECUTE, compacting fuse for LEARN, the waiting
         * look while escalated — and fires no glyph of its own, so CHECK and
         * QUESTION each fire exactly once, from the bridge. Installing Phosphor's
         * [CognitiveLookSwitch.DEFAULT] table instead would fire them twice.
         */
        val LOOK_SWITCH: CognitiveLookSwitch = CognitiveLookSwitch(
            table = CognitiveLookSwitch.DEFAULT.mapValues { (_, look) -> look.copy(glyph = null) },
            whenEscalated = CognitiveLookSwitch.ESCALATED.copy(glyph = null),
        )

        /**
         * AMPERE's six PROPEL phases onto Phosphor's enum, by name. Phosphor's
         * extra `LOOP` and `NONE` have no AMPERE event and are never produced.
         */
        fun CognitivePhase.toPhosphorPhase(): PhosphorPhase = when (this) {
            CognitivePhase.PERCEIVE -> PhosphorPhase.PERCEIVE
            CognitivePhase.RECALL -> PhosphorPhase.RECALL
            CognitivePhase.OBSERVE -> PhosphorPhase.OBSERVE
            CognitivePhase.PLAN -> PhosphorPhase.PLAN
            CognitivePhase.EXECUTE -> PhosphorPhase.EXECUTE
            CognitivePhase.LEARN -> PhosphorPhase.LEARN
        }
    }
}
