package link.socket.ampere.agents.receptors

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.FileSystemEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.util.logWith

/**
 * FileSystemReceptor monitors a workspace directory for file changes and emits
 * corresponding events to the event bus.
 *
 * This receptor enables "sensory input" for the agent nervous system by watching
 * for new files (e.g., markdown specifications, feature requests) and transforming
 * them into events that agents can react to.
 *
 * Example usage:
 * ```
 * val receptor = FileSystemReceptor(
 *     workspacePath = "~/.ampere/Workspaces/Ampere",
 *     agentEventApi = eventApi,
 *     receptorId = "file-receptor-1",
 *     scope = coroutineScope
 * )
 * receptor.start()
 * ```
 */
class FileSystemReceptor(
    private val workspacePath: String,
    private val agentEventApi: AgentEventApi,
    private val receptorId: AgentId = "receptor-filesystem",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val pollIntervalMs: Long = 500,
    private val fileFilter: (File) -> Boolean = { true },
) {
    private val logger by lazy { logWith("FileSystemReceptor") }

    private var watchService: WatchService? = null
    private var watchJob: Job? = null
    private var isRunning = false

    private val workspaceFile: File by lazy {
        File(workspacePath.replace("~", System.getProperty("user.home")))
    }

    private val workspacePathObj: Path by lazy {
        workspaceFile.toPath()
    }

    /**
     * Start monitoring the workspace directory for file changes.
     */
    fun start() {
        if (isRunning) {
            logger.w { "FileSystemReceptor already running" }
            return
        }

        if (!workspaceFile.exists()) {
            logger.e { "Workspace path does not exist: ${workspaceFile.absolutePath}" }
            return
        }

        if (!workspaceFile.isDirectory) {
            logger.e { "Workspace path is not a directory: ${workspaceFile.absolutePath}" }
            return
        }

        try {
            watchService = FileSystems.getDefault().newWatchService()
            registerDirectoryRecursive(workspacePathObj)

            isRunning = true
            logger.i { "Started FileSystemReceptor monitoring: ${workspaceFile.absolutePath}" }

            watchJob = scope.launch {
                watchLoop()
            }
        } catch (e: Exception) {
            logger.e(e) { "Failed to start FileSystemReceptor" }
        }
    }

    /**
     * Stop monitoring the workspace directory.
     */
    fun stop() {
        if (!isRunning) {
            return
        }

        isRunning = false
        watchJob?.cancel()
        watchService?.close()
        watchService = null

        logger.i { "Stopped FileSystemReceptor" }
    }

    /**
     * Register a directory and all its subdirectories with the watch service.
     */
    private fun registerDirectoryRecursive(path: Path) {
        val service = watchService ?: return

        // Register this directory
        path.register(
            service,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE
        )

        // Register all subdirectories
        path.toFile().listFiles()?.forEach { file ->
            if (file.isDirectory) {
                registerDirectoryRecursive(file.toPath())
            }
        }
    }

    /**
     * Main watch loop that processes file system events.
     */
    private suspend fun watchLoop() {
        val service = watchService ?: return

        while (isRunning) {
            val watchKey: WatchKey? = service.poll()

            if (watchKey != null) {
                processWatchKey(watchKey)
                watchKey.reset()
            } else {
                // No events, wait before polling again
                delay(pollIntervalMs)
            }
        }
    }

    /**
     * Process events from a watch key.
     */
    private suspend fun processWatchKey(watchKey: WatchKey) {
        val dirPath = watchKey.watchable() as? Path ?: return

        watchKey.pollEvents().forEach { event ->
            @Suppress("UNCHECKED_CAST")
            val typedEvent = event as? WatchEvent<Path> ?: return@forEach
            val fileName = typedEvent.context()
            val filePath = dirPath.resolve(fileName)

            // Handle new directory creation by registering it
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE &&
                filePath.toFile().isDirectory
            ) {
                registerDirectoryRecursive(filePath)
            }

            // Filter files
            if (!fileFilter(filePath.toFile())) {
                return@forEach
            }

            // Emit corresponding event
            when (event.kind()) {
                StandardWatchEventKinds.ENTRY_CREATE -> {
                    if (filePath.toFile().isFile) {
                        emitFileCreated(filePath)
                    }
                }
                StandardWatchEventKinds.ENTRY_MODIFY -> {
                    if (filePath.toFile().isFile) {
                        emitFileModified(filePath)
                    }
                }
                StandardWatchEventKinds.ENTRY_DELETE -> {
                    emitFileDeleted(filePath)
                }
            }
        }
    }

    /**
     * Emit a FileCreated event.
     */
    private suspend fun emitFileCreated(filePath: Path) {
        val event = FileSystemEvent.FileCreated(
            eventId = generateUUID(receptorId),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(receptorId),
            urgency = Urgency.MEDIUM,
            filePath = filePath.absolutePathString(),
            fileName = filePath.name,
            fileExtension = filePath.extension.takeIf { it.isNotEmpty() },
            workspacePath = workspacePathObj.absolutePathString(),
            relativePath = filePath.relativeTo(workspacePathObj).pathString
        )

        logger.i { "File created: ${event.relativePath}" }
        agentEventApi.publish(event)
    }

    /**
     * Emit a FileModified event.
     */
    private suspend fun emitFileModified(filePath: Path) {
        val event = FileSystemEvent.FileModified(
            eventId = generateUUID(receptorId),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(receptorId),
            urgency = Urgency.LOW,
            filePath = filePath.absolutePathString(),
            fileName = filePath.name,
            fileExtension = filePath.extension.takeIf { it.isNotEmpty() },
            workspacePath = workspacePathObj.absolutePathString(),
            relativePath = filePath.relativeTo(workspacePathObj).pathString
        )

        logger.d { "File modified: ${event.relativePath}" }
        agentEventApi.publish(event)
    }

    /**
     * Emit a FileDeleted event.
     */
    private suspend fun emitFileDeleted(filePath: Path) {
        val event = FileSystemEvent.FileDeleted(
            eventId = generateUUID(receptorId),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(receptorId),
            urgency = Urgency.LOW,
            filePath = filePath.absolutePathString(),
            fileName = filePath.name,
            fileExtension = filePath.extension.takeIf { it.isNotEmpty() },
            workspacePath = workspacePathObj.absolutePathString(),
            relativePath = filePath.relativeTo(workspacePathObj).pathString
        )

        logger.d { "File deleted: ${event.relativePath}" }
        agentEventApi.publish(event)
    }
}
