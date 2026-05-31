package link.socket.ampere.phosphor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.signal.AtmosphereState

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
 * [link.socket.phosphor.lumos.LumosGlyph] is queued via [voxelFrameBuilder].
 *
 * The bridge does not own a frame loop. The consumer must call [onFrameTick]
 * once per frame after [CognitiveSceneRuntime.update] so that pending
 * atmosphere targets can be flushed when the in-flight transition completes.
 */
class AmperePhosphorBridge(
    private val bus: EventSerialBus,
    private val runtime: CognitiveSceneRuntime,
    private val voxelFrameBuilder: VoxelFrameBuilder,
    private val strategy: PropelToAtmosphereStrategy = DefaultPropelStrategy,
    private val agentId: String = BRIDGE_AGENT_ID,
    private val glyphDurationSeconds: Float = DEFAULT_GLYPH_DURATION_SECONDS,
) {
    private val mutex = Mutex()
    private var pendingAtmosphere: AtmosphereState? = null
    private val subscribedEventTypes: MutableList<EventType> = mutableListOf()
    private var started: Boolean = false

    /** Register handlers on the bus. Subsequent calls while started are no-ops. */
    fun start() {
        if (started) return
        started = true

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
     * Note: [EventSerialBus.unsubscribe] removes every handler registered for
     * the affected event types, not just the bridge's. Consumers that share
     * those event types with other subscribers should defer [stop] until those
     * subscribers are also being torn down.
     */
    fun stop() {
        if (!started) return
        started = false
        for (eventType in subscribedEventTypes) {
            bus.unsubscribe(eventType)
        }
        subscribedEventTypes.clear()
    }

    private fun register(eventType: EventType, dispatch: suspend (Event) -> Unit) {
        bus.subscribe(
            agentId = agentId,
            eventType = eventType,
            handler = EventHandler<Event, Subscription> { event, _ ->
                if (started) dispatch(event)
            },
        )
        subscribedEventTypes += eventType
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
        mutex.withLock { pendingAtmosphere = null }
        runtime.setAtmosphere(AtmospherePresets.UNCERTAIN)
    }

    private fun handleGlyphSource(event: Event) {
        val glyph = strategy.glyphFor(event) ?: return
        voxelFrameBuilder.queueGlyph(glyph, glyphDurationSeconds)
    }

    companion object {
        const val BRIDGE_AGENT_ID: String = "ampere-phosphor-bridge"
        const val DEFAULT_GLYPH_DURATION_SECONDS: Float = 1.5f
    }
}
