package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.event.CognitiveStateSnapshot
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.SparkAppliedEvent
import link.socket.ampere.agents.domain.event.SparkRemovedEvent
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * An agent that emits cognitive state events through the EventSerialBus.
 *
 * This abstract class extends [AutonomousAgent] to add observability for
 * cognitive state transitions. When Sparks are pushed or popped, events
 * are emitted that make the agent's cognitive evolution visible.
 *
 * Rather than modifying [AutonomousAgent] directly (which would create
 * a dependency on EventSerialBus for all agents), this separate class
 * allows agents to opt-in to observability by extending ObservableAgent
 * instead of AutonomousAgent.
 *
 * @param eventApi The event API for publishing cognitive events
 * @param observabilityScope CoroutineScope for async event publishing
 */
@Serializable
abstract class ObservableAgent<S : AgentState>(
    @kotlinx.serialization.Transient
    protected val eventApi: AgentEventApi? = null,
    @kotlinx.serialization.Transient
    private val observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AutonomousAgent<S>() {

    /**
     * Emits a SparkAppliedEvent when a Spark is pushed onto the stack.
     */
    override fun onSparkApplied(spark: Spark) {
        eventApi?.let { api ->
            val event = SparkAppliedEvent(
                eventId = generateUUID(id),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Agent(id),
                agentId = id,
                stackDepth = sparkDepth,
                stackDescription = cognitiveState,
                sparkName = spark.name,
                sparkType = spark::class.simpleName ?: "Unknown",
            )
            observabilityScope.launch {
                api.publish(event)
            }
        }
    }

    /**
     * Emits a SparkRemovedEvent when a Spark is popped from the stack.
     */
    override fun onSparkRemoved(previousSpark: Spark?) {
        eventApi?.let { api ->
            val event = SparkRemovedEvent(
                eventId = generateUUID(id),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Agent(id),
                agentId = id,
                stackDepth = sparkDepth,
                stackDescription = cognitiveState,
                previousSparkName = previousSpark?.name ?: "Unknown",
            )
            observabilityScope.launch {
                api.publish(event)
            }
        }
    }

    /**
     * Emits a CognitiveStateSnapshot capturing the full current state.
     *
     * Call this at significant lifecycle points or periodically for
     * detailed observability of the agent's cognitive context.
     */
    fun emitCognitiveSnapshot() {
        eventApi?.let { api ->
            val sparkNames = buildList {
                // Parse names from the cognitiveState description
                val parts = cognitiveState.split(" → ")
                // Skip the first element (affinity) and extract Spark names
                parts.drop(1).forEach { part ->
                    add(part.removeSurrounding("[", "]"))
                }
            }

            val event = CognitiveStateSnapshot(
                eventId = generateUUID(id),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Agent(id),
                agentId = id,
                stackDepth = sparkDepth,
                stackDescription = cognitiveState,
                affinity = affinity.name,
                sparkNames = sparkNames,
                effectivePromptLength = currentSystemPrompt.length,
                availableToolCount = availableTools?.size,
            )
            observabilityScope.launch {
                api.publish(event)
            }
        }
    }
}
