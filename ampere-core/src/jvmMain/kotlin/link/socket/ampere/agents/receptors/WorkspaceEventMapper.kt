package link.socket.ampere.agents.receptors

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.FileSystemEvent
import link.socket.ampere.agents.domain.event.ProductEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.util.logWith

/**
 * WorkspaceEventMapper subscribes to FileSystemEvents and maps them to
 * domain-specific events (e.g., ProductEvent).
 *
 * This component acts as a "sensory interpretation" layer, taking raw
 * file system events and transforming them into meaningful domain events
 * that agents can act upon.
 *
 * Example:
 * - FileCreated(Strategy/Act 2/Epic-1.md) → ProductEvent.EpicDefined
 * - FileCreated(Features/NewFeature.md) → ProductEvent.FeatureRequested
 */
class WorkspaceEventMapper(
    private val agentEventApi: AgentEventApi,
    private val mapperId: AgentId = "mapper-workspace",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val logger by lazy { logWith("WorkspaceEventMapper") }

    /**
     * Start listening for FileSystemEvents and mapping them to domain events.
     */
    fun start() {
        logger.i { "Starting WorkspaceEventMapper" }

        // Get access to the event bus through reflection or expose it
        // For now, we'll need to accept the EventBus as a parameter
        // This is a temporary workaround - in production, expose eventBus from AgentEventApi
        logger.w { "WorkspaceEventMapper requires direct EventBus access - consider refactoring" }
    }

    /**
     * Start with direct event bus access.
     */
    fun startWithEventBus(eventBus: link.socket.ampere.agents.events.bus.EventSerialBus) {
        logger.i { "Starting WorkspaceEventMapper with EventBus" }

        // Subscribe to FileCreated events
        eventBus.subscribe<FileSystemEvent.FileCreated, EventSubscription.ByEventClassType>(
            agentId = mapperId,
            eventType = FileSystemEvent.FileCreated.EVENT_TYPE,
        ) { event, _ ->
            scope.launch {
                handleFileCreated(event)
            }
        }

        // Subscribe to FileModified events
        eventBus.subscribe<FileSystemEvent.FileModified, EventSubscription.ByEventClassType>(
            agentId = mapperId,
            eventType = FileSystemEvent.FileModified.EVENT_TYPE,
        ) { event, _ ->
            scope.launch {
                handleFileModified(event)
            }
        }
    }

    /**
     * Handle FileCreated events by parsing and mapping to domain events.
     */
    private suspend fun handleFileCreated(event: FileSystemEvent.FileCreated) {
        logger.d { "Processing file created: ${event.relativePath}" }

        // Only process markdown files
        if (event.fileExtension != "md") {
            return
        }

        val file = File(event.filePath)
        if (!file.exists()) {
            logger.w { "File no longer exists: ${event.filePath}" }
            return
        }

        // Parse the markdown content
        val content = MarkdownContentParser.parseFile(file) ?: run {
            logger.w { "Failed to parse markdown file: ${event.filePath}" }
            return
        }

        // Extract path information for context
        val pathParts = extractPathContext(event.relativePath)

        // Map to appropriate domain event based on content type
        when (content.contentType) {
            MarkdownContent.ContentType.FEATURE -> {
                emitFeatureRequested(content, event, pathParts)
            }
            MarkdownContent.ContentType.EPIC -> {
                emitEpicDefined(content, event, pathParts)
            }
            MarkdownContent.ContentType.PHASE -> {
                emitPhaseDefined(content, event, pathParts)
            }
            MarkdownContent.ContentType.UNKNOWN -> {
                // Default to feature request if unclear
                logger.d { "Unknown content type, defaulting to feature: ${event.relativePath}" }
                emitFeatureRequested(content, event, pathParts)
            }
        }
    }

    /**
     * Handle FileModified events (could re-parse and update).
     */
    private suspend fun handleFileModified(event: FileSystemEvent.FileModified) {
        // For now, we'll treat modifications like new files
        // In a more sophisticated implementation, we might emit update events
        logger.d { "File modified: ${event.relativePath} (not yet implemented)" }
    }

    /**
     * Extract contextual information from the file path.
     *
     * Example: "Strategy/Act 2/Phase 1/Epic-1.md"
     * Returns: PathContext(act = "Act 2", phase = "Phase 1", epic = null)
     */
    private fun extractPathContext(relativePath: String): PathContext {
        val parts = relativePath.split('/', '\\')

        var act: String? = null
        var phase: String? = null
        var epic: String? = null

        parts.forEach { part ->
            when {
                part.startsWith("Act ", ignoreCase = true) -> act = part
                part.startsWith("Phase ", ignoreCase = true) -> phase = part
                part.startsWith("Epic", ignoreCase = true) -> epic = part.removeSuffix(".md")
            }
        }

        return PathContext(act = act, phase = phase, epic = epic)
    }

    /**
     * Emit a ProductEvent.FeatureRequested event.
     */
    private suspend fun emitFeatureRequested(
        content: MarkdownContent,
        sourceEvent: FileSystemEvent.FileCreated,
        pathContext: PathContext,
    ) {
        val event = ProductEvent.FeatureRequested(
            eventId = generateUUID(mapperId),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(mapperId),
            urgency = determineUrgency(content.metadata),
            featureTitle = content.title,
            description = content.description,
            phase = content.metadata["Phase"] ?: pathContext.phase,
            epic = content.metadata["Epic"] ?: pathContext.epic,
            act = content.metadata["Act"] ?: pathContext.act,
            requestedBy = mapperId,
            metadata = content.metadata,
            sourceFilePath = sourceEvent.filePath,
        )

        logger.i { "Emitting FeatureRequested: ${event.featureTitle}" }
        agentEventApi.publish(event)
    }

    /**
     * Emit a ProductEvent.EpicDefined event.
     */
    private suspend fun emitEpicDefined(
        content: MarkdownContent,
        sourceEvent: FileSystemEvent.FileCreated,
        pathContext: PathContext,
    ) {
        val event = ProductEvent.EpicDefined(
            eventId = generateUUID(mapperId),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(mapperId),
            urgency = determineUrgency(content.metadata),
            epicTitle = content.title,
            description = content.description,
            phase = content.metadata["Phase"] ?: pathContext.phase,
            act = content.metadata["Act"] ?: pathContext.act,
            metadata = content.metadata,
            sourceFilePath = sourceEvent.filePath,
        )

        logger.i { "Emitting EpicDefined: ${event.epicTitle}" }
        agentEventApi.publish(event)
    }

    /**
     * Emit a ProductEvent.PhaseDefined event.
     */
    private suspend fun emitPhaseDefined(
        content: MarkdownContent,
        sourceEvent: FileSystemEvent.FileCreated,
        pathContext: PathContext,
    ) {
        val event = ProductEvent.PhaseDefined(
            eventId = generateUUID(mapperId),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(mapperId),
            urgency = determineUrgency(content.metadata),
            phaseTitle = content.title,
            description = content.description,
            act = content.metadata["Act"] ?: pathContext.act,
            metadata = content.metadata,
            sourceFilePath = sourceEvent.filePath,
        )

        logger.i { "Emitting PhaseDefined: ${event.phaseTitle}" }
        agentEventApi.publish(event)
    }

    /**
     * Determine urgency from metadata.
     */
    private fun determineUrgency(metadata: Map<String, String>): Urgency {
        return when (metadata["Priority"]?.lowercase()) {
            "high", "critical" -> Urgency.HIGH
            "low" -> Urgency.LOW
            else -> Urgency.MEDIUM
        }
    }

    /**
     * Context extracted from file path.
     */
    private data class PathContext(
        val act: String? = null,
        val phase: String? = null,
        val epic: String? = null,
    )
}
