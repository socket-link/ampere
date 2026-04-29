package link.socket.ampere.trace

import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.event.SparkAppliedEvent
import link.socket.ampere.agents.domain.event.SparkRemovedEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.db.memory.KnowledgeStore
import link.socket.ampere.db.memory.OutcomeMemoryStore
import link.socket.ampere.util.ioDispatcher

class ArcTraceProjection(
    private val database: Database,
    private val json: Json = DEFAULT_JSON,
    private val wattCostAggregator: WattCostAggregator = WattCostAggregator(),
) {
    suspend fun project(runId: ArcRunId): Result<ArcRunTrace> =
        project(runId = runId, arcId = runId)

    suspend fun project(
        runId: ArcRunId,
        arcId: ArcId,
    ): Result<ArcRunTrace> = withContext(ioDispatcher) {
        runCatching {
            val eventRows = database.eventStoreQueries
                .getEventsByRunIdOrPayload(run_id = runId)
                .executeAsList()
            val events = eventRows.mapNotNull { row -> row.decodeOrNull() }

            val knowledgeRows = database.knowledgeStoreQueries
                .findKnowledgeByRunId(runId)
                .executeAsList()
            val outcomeRows = database.outcomeMemoryStoreQueries
                .getOutcomesByRunId(runId)
                .executeAsList()

            if (events.isEmpty() && knowledgeRows.isEmpty() && outcomeRows.isEmpty()) {
                error("No trace data found for runId=$runId")
            }

            val modelInvocations = buildModelInvocations(events)
            val toolCalls = buildToolCalls(events)
            val memoryWrites = buildMemoryWrites(events, knowledgeRows, outcomeRows)
            val phases = buildPhases(events, modelInvocations, memoryWrites, toolCalls)

            val startedAt = phases.minOfOrNull { it.startedAt }
                ?: events.minOfOrNull { it.event.timestamp }
                ?: knowledgeRows.minOfOrNull { Instant.fromEpochMilliseconds(it.timestamp) }
                ?: outcomeRows.minOfOrNull { Instant.fromEpochMilliseconds(it.timestamp) }
                ?: error("No trace timestamps found for runId=$runId")
            val endedAt = phases.mapNotNull { it.endedAt }.maxOrNull()

            ArcRunTrace(
                runId = runId,
                arcId = arcId,
                startedAt = startedAt,
                endedAt = endedAt,
                phases = phases,
            )
        }
    }

    private fun EventStore.decodeOrNull(): DecodedEvent? {
        return try {
            DecodedEvent(
                event = json.decodeFromString(Event.serializer(), payload),
                payload = payload,
            )
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun buildModelInvocations(events: List<DecodedEvent>): List<ModelInvocationTrace> {
        val starts = events
            .map { it.event }
            .filterIsInstance<ProviderCallStartedEvent>()
            .toMutableList()

        return events
            .map { it.event }
            .filterIsInstance<ProviderCallCompletedEvent>()
            .map { completed ->
                val start = starts.firstOrNull { candidate ->
                    candidate.timestamp <= completed.timestamp &&
                        candidate.workflowId == completed.workflowId &&
                        candidate.agentId == completed.agentId &&
                        candidate.providerId == completed.providerId &&
                        candidate.modelId == completed.modelId &&
                        candidate.cognitivePhase == completed.cognitivePhase
                }
                if (start != null) {
                    starts.remove(start)
                }

                val invocation = ModelInvocationTrace(
                    eventId = completed.eventId,
                    agentId = completed.agentId,
                    phaseName = completed.cognitivePhase?.name ?: UNKNOWN_PHASE,
                    providerId = completed.providerId,
                    modelId = completed.modelId,
                    startedAt = start?.timestamp
                        ?: Instant.fromEpochMilliseconds(
                            completed.timestamp.toEpochMilliseconds() - completed.latencyMs,
                        ),
                    endedAt = completed.timestamp,
                    routingReason = start?.routingReason,
                    inputTokens = completed.usage.inputTokens,
                    outputTokens = completed.usage.outputTokens,
                    estimatedUsd = completed.usage.estimatedCost,
                    latencyMs = completed.latencyMs,
                    success = completed.success,
                    errorType = completed.errorType,
                )

                invocation.copy(wattCost = wattCostAggregator.costFor(invocation))
            }
    }

    private fun buildToolCalls(events: List<DecodedEvent>): List<ToolCallTrace> {
        val startsByInvocation = events
            .map { it.event }
            .filterIsInstance<ToolEvent.ToolExecutionStarted>()
            .associateBy { it.invocationId }

        val completedInvocationIds = mutableSetOf<String>()
        val completedCalls = events
            .map { it.event }
            .filterIsInstance<ToolEvent.ToolExecutionCompleted>()
            .map { completed ->
                completedInvocationIds += completed.invocationId
                val start = startsByInvocation[completed.invocationId]
                ToolCallTrace(
                    invocationId = completed.invocationId,
                    phaseName = phaseNameFor(start ?: completed, default = EXECUTE_PHASE) ?: EXECUTE_PHASE,
                    toolId = completed.toolId,
                    toolName = completed.toolName,
                    startedAt = start?.timestamp
                        ?: Instant.fromEpochMilliseconds(
                            completed.timestamp.toEpochMilliseconds() - completed.durationMs,
                        ),
                    endedAt = completed.timestamp,
                    durationMs = completed.durationMs,
                    success = completed.success,
                    errorMessage = completed.errorMessage,
                )
            }

        val pendingCalls = startsByInvocation.values
            .filterNot { it.invocationId in completedInvocationIds }
            .map { started ->
                ToolCallTrace(
                    invocationId = started.invocationId,
                    phaseName = phaseNameFor(started, default = EXECUTE_PHASE) ?: EXECUTE_PHASE,
                    toolId = started.toolId,
                    toolName = started.toolName,
                    startedAt = started.timestamp,
                )
            }

        return (completedCalls + pendingCalls).sortedBy { it.startedAt }
    }

    private fun buildMemoryWrites(
        events: List<DecodedEvent>,
        knowledgeRows: List<KnowledgeStore>,
        outcomeRows: List<OutcomeMemoryStore>,
    ): List<MemoryWriteTrace> {
        val writesById = linkedMapOf<String, MemoryWriteTrace>()

        for (row in knowledgeRows) {
            writesById[row.id] = row.toMemoryWriteTrace()
        }

        events
            .map { it.event }
            .filterIsInstance<MemoryEvent.KnowledgeStored>()
            .forEach { event ->
                val stored = database.knowledgeStoreQueries
                    .getKnowledgeById(event.knowledgeId)
                    .executeAsOneOrNull()

                writesById[event.knowledgeId] = stored?.toMemoryWriteTrace(
                    phaseName = LEARN_PHASE,
                    fallbackTags = event.tags,
                ) ?: MemoryWriteTrace(
                    id = event.knowledgeId,
                    phaseName = LEARN_PHASE,
                    timestamp = event.timestamp,
                    memoryType = event.knowledgeType.name,
                    sourceId = event.sourceId,
                    taskType = event.taskType,
                    tags = event.tags,
                    approach = event.approach,
                    learnings = event.learnings,
                )
            }

        for (row in outcomeRows) {
            writesById[row.id] = MemoryWriteTrace(
                id = row.id,
                phaseName = EXECUTE_PHASE,
                timestamp = Instant.fromEpochMilliseconds(row.timestamp),
                memoryType = OUTCOME_MEMORY_TYPE,
                sourceId = row.ticket_id,
                approach = row.approach,
                learnings = row.error_message,
            )
        }

        return writesById.values.sortedBy { it.timestamp }
    }

    private fun KnowledgeStore.toMemoryWriteTrace(
        phaseName: String = LEARN_PHASE,
        fallbackTags: List<String> = emptyList(),
    ): MemoryWriteTrace {
        val tags = database.knowledgeStoreQueries
            .getTagsForKnowledge(id)
            .executeAsList()
            .ifEmpty { fallbackTags }

        return MemoryWriteTrace(
            id = id,
            phaseName = phaseName,
            timestamp = Instant.fromEpochMilliseconds(timestamp),
            memoryType = knowledge_type,
            sourceId = outcome_id ?: idea_id ?: perception_id ?: plan_id ?: task_id,
            taskType = task_type,
            tags = tags,
            approach = approach,
            learnings = learnings,
        )
    }

    private fun buildPhases(
        events: List<DecodedEvent>,
        modelInvocations: List<ModelInvocationTrace>,
        memoryWrites: List<MemoryWriteTrace>,
        toolCalls: List<ToolCallTrace>,
    ): List<PropelPhase> {
        val eventsByPhase = linkedMapOf<String, MutableList<TraceEvent>>()
        var activePhase: String? = null

        for (decoded in events) {
            val event = decoded.event
            val explicitPhase = phaseNameFor(event, default = activePhase)
            val phaseName = explicitPhase ?: activePhase ?: RUN_PHASE
            eventsByPhase.getOrPut(phaseName) { mutableListOf() }
                .add(decoded.toTraceEvent())

            activePhase = when (event) {
                is SparkAppliedEvent -> event.phaseSparkName() ?: activePhase
                is SparkRemovedEvent -> null
                else -> explicitPhase ?: activePhase
            }
        }

        val phaseNames = (
            eventsByPhase.keys +
                modelInvocations.map { it.phaseName } +
                memoryWrites.map { it.phaseName } +
                toolCalls.map { it.phaseName }
            )
            .filter { it.isNotBlank() }
            .distinct()

        return phaseNames.mapNotNull { phaseName ->
            val phaseEvents = eventsByPhase[phaseName].orEmpty().sortedBy { it.timestamp }
            val phaseModels = modelInvocations.filter { it.phaseName == phaseName }
            val phaseMemoryWrites = memoryWrites.filter { it.phaseName == phaseName }
            val phaseToolCalls = toolCalls.filter { it.phaseName == phaseName }

            val timestamps = buildList {
                addAll(phaseEvents.map { it.timestamp })
                addAll(phaseModels.map { it.startedAt })
                addAll(phaseModels.mapNotNull { it.endedAt })
                addAll(phaseMemoryWrites.map { it.timestamp })
                addAll(phaseToolCalls.map { it.startedAt })
                addAll(phaseToolCalls.mapNotNull { it.endedAt })
            }

            val startedAt = timestamps.minOrNull() ?: return@mapNotNull null
            val endedAt = timestamps.maxOrNull()

            PropelPhase(
                name = phaseName,
                startedAt = startedAt,
                endedAt = endedAt,
                events = phaseEvents,
                modelInvocations = phaseModels,
                memoryWrites = phaseMemoryWrites,
                toolCalls = phaseToolCalls,
                wattCost = wattCostAggregator.aggregate(phaseModels),
            )
        }.sortedBy { it.startedAt }
    }

    private fun DecodedEvent.toTraceEvent(): TraceEvent {
        return TraceEvent(
            eventId = event.eventId,
            eventType = event.eventType,
            timestamp = event.timestamp,
            sourceId = event.eventSource.getIdentifier(),
            summary = event.getSummary(
                formatUrgency = { urgency -> urgency.name },
                formatSource = { source -> source.getIdentifier() },
            ),
            payload = payload,
        )
    }

    private fun phaseNameFor(event: Event, default: String? = null): String? = when (event) {
        is ProviderCallStartedEvent -> event.cognitivePhase?.name
        is ProviderCallCompletedEvent -> event.cognitivePhase?.name
        is RoutingEvent.RouteSelected -> event.phase?.name
        is RoutingEvent.RouteFallback -> event.phase?.name
        is MemoryEvent.KnowledgeRecalled -> RECALL_PHASE
        is MemoryEvent.KnowledgeStored -> LEARN_PHASE
        is ToolEvent.ToolExecutionStarted -> default ?: EXECUTE_PHASE
        is ToolEvent.ToolExecutionCompleted -> default ?: EXECUTE_PHASE
        is SparkAppliedEvent -> event.phaseSparkName() ?: default
        is SparkRemovedEvent -> event.phaseSparkName() ?: default
        else -> default
    }

    private fun SparkAppliedEvent.phaseSparkName(): String? =
        sparkName.removePrefix("Phase:")
            .takeIf { it != sparkName && it.isNotBlank() }
            ?.uppercase()

    private fun SparkRemovedEvent.phaseSparkName(): String? =
        previousSparkName.removePrefix("Phase:")
            .takeIf { it != previousSparkName && it.isNotBlank() }
            ?.uppercase()

    private data class DecodedEvent(
        val event: Event,
        val payload: String,
    )

    private companion object {
        const val RUN_PHASE = "RUN"
        const val UNKNOWN_PHASE = "UNKNOWN"
        const val RECALL_PHASE = "RECALL"
        const val EXECUTE_PHASE = "EXECUTE"
        const val LEARN_PHASE = "LEARN"
        const val OUTCOME_MEMORY_TYPE = "OUTCOME"
    }
}
