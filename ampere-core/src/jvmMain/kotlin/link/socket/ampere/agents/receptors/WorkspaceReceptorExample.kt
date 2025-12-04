package link.socket.ampere.agents.receptors

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.domain.event.ProductEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.util.logWith
import java.io.File

/**
 * Example demonstrating how to set up and use the FileSystemReceptor
 * to monitor a workspace directory and emit ProductEvents.
 *
 * This example shows:
 * 1. Creating the event infrastructure (EventBus, Repository, API)
 * 2. Starting the FileSystemReceptor to monitor a directory
 * 3. Starting the WorkspaceEventMapper to transform file events into domain events
 * 4. Subscribing to ProductEvents (simulating a PM Agent)
 * 5. Watching for events and logging them
 *
 * Usage:
 * ```
 * val example = WorkspaceReceptorExample(
 *     workspacePath = "~/.ampere/Workspaces/Ampere"
 * )
 * example.start()
 * ```
 */
class WorkspaceReceptorExample(
    private val workspacePath: String,
    private val eventRepository: EventRepository,
) {
    private val logger by lazy { logWith("WorkspaceReceptorExample") }
    private val scope = CoroutineScope(Dispatchers.IO)

    private lateinit var receptor: FileSystemReceptor
    private lateinit var mapper: WorkspaceEventMapper
    private lateinit var eventApi: AgentEventApi
    private lateinit var eventBus: EventSerialBus

    fun start() {
        logger.i { "Starting Workspace Receptor Example" }
        logger.i { "Monitoring workspace: $workspacePath" }

        // 1. Create event bus
        val eventBusFactory = EventSerialBusFactory(scope = scope)
        eventBus = eventBusFactory.create()

        // 2. Create AgentEventApi for the receptor and mapper
        eventApi = AgentEventApi(
            agentId = "workspace-receptor-system",
            eventRepository = eventRepository,
            eventSerialBus = eventBus,
        )

        // 3. Subscribe to ProductEvents (simulating a PM Agent)
        subscribeToProductEvents()

        // 4. Create and start the workspace event mapper
        mapper = WorkspaceEventMapper(
            agentEventApi = eventApi,
            mapperId = "mapper-workspace",
            scope = scope
        )
        mapper.startWithEventBus(eventBus)
        logger.i { "Started WorkspaceEventMapper" }

        // 5. Create and start the file system receptor
        receptor = FileSystemReceptor(
            workspacePath = workspacePath,
            agentEventApi = eventApi,
            receptorId = "receptor-filesystem",
            scope = scope,
            fileFilter = { file ->
                // Only monitor markdown files
                file.extension.lowercase() == "md"
            }
        )
        receptor.start()
        logger.i { "Started FileSystemReceptor" }

        logger.i { "System is now watching for file changes..." }
        logger.i { "Try adding a markdown file to: ${expandPath(workspacePath)}" }
    }

    fun stop() {
        logger.i { "Stopping Workspace Receptor Example" }
        receptor.stop()
        logger.i { "System stopped" }
    }

    /**
     * Subscribe to all ProductEvent types and log them.
     * In a real implementation, this would be handled by a PM Agent
     * that creates tickets and tasks.
     */
    private fun subscribeToProductEvents() {
        // Subscribe to FeatureRequested events
        eventBus.subscribe<ProductEvent.FeatureRequested, EventSubscription.ByEventClassType>(
            agentId = "pm-agent-simulator",
            eventType = ProductEvent.FeatureRequested.EVENT_TYPE
        ) { event, _ ->
            logger.i { "\n=== FEATURE REQUESTED ===" }
            logger.i { "Title: ${event.featureTitle}" }
            logger.i { "Description: ${event.description.take(100)}..." }
            logger.i { "Act: ${event.act ?: "N/A"}" }
            logger.i { "Phase: ${event.phase ?: "N/A"}" }
            logger.i { "Epic: ${event.epic ?: "N/A"}" }
            logger.i { "Source File: ${event.sourceFilePath}" }
            logger.i { "========================\n" }

            // TODO: Create tickets via PM Agent
            // productManagerAgent.createTicketsForFeature(event)
        }

        // Subscribe to EpicDefined events
        eventBus.subscribe<ProductEvent.EpicDefined, EventSubscription.ByEventClassType>(
            agentId = "pm-agent-simulator",
            eventType = ProductEvent.EpicDefined.EVENT_TYPE
        ) { event, _ ->
            logger.i { "\n=== EPIC DEFINED ===" }
            logger.i { "Title: ${event.epicTitle}" }
            logger.i { "Description: ${event.description.take(100)}..." }
            logger.i { "Act: ${event.act ?: "N/A"}" }
            logger.i { "Phase: ${event.phase ?: "N/A"}" }
            logger.i { "Source File: ${event.sourceFilePath}" }
            logger.i { "====================\n" }

            // TODO: Create epic structure via PM Agent
            // productManagerAgent.createEpicStructure(event)
        }

        // Subscribe to PhaseDefined events
        eventBus.subscribe<ProductEvent.PhaseDefined, EventSubscription.ByEventClassType>(
            agentId = "pm-agent-simulator",
            eventType = ProductEvent.PhaseDefined.EVENT_TYPE
        ) { event, _ ->
            logger.i { "\n=== PHASE DEFINED ===" }
            logger.i { "Title: ${event.phaseTitle}" }
            logger.i { "Description: ${event.description.take(100)}..." }
            logger.i { "Act: ${event.act ?: "N/A"}" }
            logger.i { "Source File: ${event.sourceFilePath}" }
            logger.i { "=====================\n" }

            // TODO: Create phase structure via PM Agent
            // productManagerAgent.createPhaseStructure(event)
        }

        logger.i { "Subscribed to ProductEvents" }
    }

    private fun expandPath(path: String): String {
        return path.replace("~", System.getProperty("user.home"))
    }
}

/**
 * Main function to run the example.
 *
 * Usage from command line:
 * ```
 * ./gradlew :ampere-core:runWorkspaceReceptorExample --args="~/.ampere/Workspaces/Ampere"
 * ```
 */
fun main(args: Array<String>) = runBlocking {
    val logger = logWith("Main")

    if (args.isEmpty()) {
        logger.e { "Usage: WorkspaceReceptorExample <workspace-path>" }
        logger.e { "Example: WorkspaceReceptorExample ~/.ampere/Workspaces/Ampere" }
        return@runBlocking
    }

    val workspacePath = args[0]
    val expandedPath = workspacePath.replace("~", System.getProperty("user.home"))

    // Verify workspace exists
    val workspaceFile = File(expandedPath)
    if (!workspaceFile.exists()) {
        logger.e { "Workspace path does not exist: $expandedPath" }
        logger.i { "Creating workspace directory..." }
        workspaceFile.mkdirs()
    }

    // Create event repository (you'll need to provide actual implementation)
    // This is a placeholder - in real usage, inject the actual EventRepository
    logger.w { "Note: EventRepository needs to be provided with actual database driver" }
    logger.w { "This example will run but events won't be persisted without a real repository" }

    // For now, we can't create a real example without the database driver
    // The user will need to integrate this into their application where
    // the EventRepository is already set up

    logger.i { "To use this receptor system:" }
    logger.i { "1. Create an EventRepository with your database driver" }
    logger.i { "2. Create a WorkspaceReceptorExample instance with the repository" }
    logger.i { "3. Call start() to begin monitoring" }
    logger.i { "4. Add markdown files to your workspace to trigger events" }
}
