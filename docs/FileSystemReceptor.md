# FileSystem Receptor System

The FileSystem Receptor system provides "sensory input" for the AMPERE agent nervous system by monitoring workspace directories for file changes and transforming them into domain events that agents can react to.

## Overview

This system consists of three main components:

1. **FileSystemReceptor** - Monitors a directory for file changes (create, modify, delete)
2. **WorkspaceEventMapper** - Transforms FileSystemEvents into domain-specific ProductEvents
3. **Event Handlers** - Agents (like ProductManagerAgent) that react to ProductEvents

## Architecture

```
Workspace Directory
        ↓
   [File Created]
        ↓
FileSystemReceptor
        ↓
  FileSystemEvent.FileCreated
        ↓
WorkspaceEventMapper
        ↓
  ProductEvent.FeatureRequested
        ↓
  ProductManagerAgent
        ↓
  Create Tickets & Tasks
```

## Event Types

### FileSystemEvent

Low-level events representing file system changes:

- `FileCreated` - A new file was created
- `FileModified` - An existing file was modified
- `FileDeleted` - A file was deleted

Each event contains:
- `filePath` - Absolute path to the file
- `fileName` - Name of the file
- `fileExtension` - File extension (e.g., "md")
- `workspacePath` - Root workspace path
- `relativePath` - Path relative to workspace root

### ProductEvent

Domain-level events representing product management activities:

- `FeatureRequested` - A feature request from a specification file
- `EpicDefined` - An epic definition
- `PhaseDefined` - A phase definition

Each event contains:
- `title` - The feature/epic/phase title
- `description` - Detailed description
- `act`, `phase`, `epic` - Organizational hierarchy
- `metadata` - Additional key-value metadata
- `sourceFilePath` - Original file that triggered the event

## Markdown File Format

The system parses markdown files with the following structure:

```markdown
# Feature Title

This is the description of the feature.
It can span multiple paragraphs and include
any markdown formatting.

## Metadata
- Act: Act 2
- Phase: Phase 1
- Epic: User Authentication
- Priority: High
- Type: Feature
```

### Content Type Detection

The system automatically detects content type based on:

1. **Metadata `Type` field** (highest priority)
2. **File path patterns** (e.g., `/feature/`, `/epic/`, `/phase/`)
3. **Title patterns** (e.g., "Feature: ..." or "Epic: ...")
4. **Default**: Treats as `Feature` if unclear

### Metadata Fields

Supported metadata fields:

- `Type` - Content type: Feature, Epic, or Phase
- `Act` - Act identifier (e.g., "Act 2")
- `Phase` - Phase identifier (e.g., "Phase 1")
- `Epic` - Epic identifier (e.g., "User Management")
- `Priority` - Priority level: High, Medium, Low

## Usage

### 1. Basic Setup

```kotlin
import link.socket.ampere.agents.receptors.FileSystemReceptor
import link.socket.ampere.agents.receptors.WorkspaceEventMapper
import link.socket.ampere.agents.events.api.AgentEventApi

// Create event infrastructure
val eventBus = EventSerialBusFactory.createThreadSafeEventBus()
val eventApi = AgentEventApi(
    agentId = "my-agent",
    eventRepository = eventRepository,
    eventSerialBus = eventBus
)

// Start the workspace event mapper
val mapper = WorkspaceEventMapper(
    agentEventApi = eventApi,
    mapperId = "workspace-mapper"
)
mapper.start()

// Start the file system receptor
val receptor = FileSystemReceptor(
    workspacePath = "~/.ampere/Workspaces/Ampere",
    agentEventApi = eventApi,
    receptorId = "file-receptor",
    fileFilter = { file -> file.extension == "md" }
)
receptor.start()
```

### 2. Subscribing to Events

#### Option A: Using Generic Subscribe

```kotlin
eventApi.subscribe<ProductEvent.FeatureRequested>(
    eventType = ProductEvent.FeatureRequested.EVENT_TYPE
) { event, _ ->
    println("Feature requested: ${event.featureTitle}")
    // Handle the event
}
```

#### Option B: Direct EventBus Subscribe

```kotlin
eventBus.subscribe<ProductEvent.FeatureRequested>(
    agentId = "pm-agent",
    eventType = ProductEvent.FeatureRequested.EVENT_TYPE
) { event, _ ->
    // Handle the event
}
```

### 3. Integrating with ProductManagerAgent

```kotlin
// The PM Agent would subscribe to ProductEvents
class ProductManagerEventHandler(
    private val pmAgent: ProductManagerAgent,
    private val eventApi: AgentEventApi
) {
    fun start() {
        // Subscribe to feature requests
        eventApi.subscribe<ProductEvent.FeatureRequested>(
            ProductEvent.FeatureRequested.EVENT_TYPE
        ) { event, _ ->
            // Create tasks from the feature request
            val task = Task.CodeChange(
                id = generateUUID("task"),
                description = event.featureTitle,
                status = TaskStatus.Pending,
                assignedTo = null
            )

            // Have PM Agent plan and create tickets
            val plan = pmAgent.determinePlanForTask(task)
            // ... create tickets in the system
        }
    }
}
```

### 4. Custom File Filters

You can filter which files trigger events:

```kotlin
val receptor = FileSystemReceptor(
    workspacePath = workspacePath,
    agentEventApi = eventApi,
    fileFilter = { file ->
        // Only process markdown files in Strategy directory
        file.extension == "md" &&
        file.path.contains("Strategy")
    }
)
```

## Example Workflow

### 1. Create a Feature Specification

Create file: `~/.ampere/Workspaces/Ampere/Strategy/Act 2/Phase 1/NewFeature.md`

```markdown
# GitHub Issue Receptor

Implement a receptor that polls the socket-link/ampere repository
for new issues tagged with 'ampere-task' and creates TaskDetectedEvents.

## Metadata
- Act: Act 2
- Phase: Phase 1
- Epic: Sensory Input System
- Priority: High
- Type: Feature
```

### 2. FileSystemReceptor Detects the File

```
FileSystemReceptor: File created: Strategy/Act 2/Phase 1/NewFeature.md
```

### 3. WorkspaceEventMapper Parses and Emits Event

```
WorkspaceEventMapper: Emitting FeatureRequested: GitHub Issue Receptor
```

### 4. ProductManagerAgent Receives Event

```
PM Agent: Received feature request
  - Title: GitHub Issue Receptor
  - Epic: Sensory Input System
  - Phase: Phase 1
  - Creating tickets...
```

### 5. Tickets Created

The PM Agent would create:
- Epic ticket: "Sensory Input System"
- Feature ticket: "GitHub Issue Receptor"
- Task tickets: Implementation subtasks

## Monitoring Events

Use the Ampere CLI to watch events in real-time:

```bash
# Watch all FileSystem events
./ampere-cli/ampere watch --filter FileCreated

# Watch all Product events
./ampere-cli/ampere watch --filter ProductFeatureRequested

# Watch events from the mapper
./ampere-cli/ampere watch --agent mapper-workspace
```

## Directory Structure

```
~/.ampere/Workspaces/
└── Ampere/
    └── Strategy/
        ├── Act 1/
        │   ├── Phase 1/
        │   │   ├── Epic-1.md
        │   │   └── Epic-2.md
        │   └── Phase 2/
        │       └── Epic-3.md
        └── Act 2/
            ├── Phase 1/
            │   ├── Epic-1.md
            │   ├── Epic-2.md
            │   └── Features/
            │       ├── Feature-1.md
            │       └── Feature-2.md
            └── Phase 2/
                └── Epic-3.md
```

## Advanced Features

### Custom Content Parsers

You can extend the markdown parser for custom formats:

```kotlin
object CustomMarkdownParser : MarkdownContentParser {
    override fun parseContent(
        content: String,
        fileName: String,
        filePath: String
    ): MarkdownContent {
        // Custom parsing logic
    }
}
```

### Event Transformation Pipeline

You can add intermediate transformers:

```kotlin
class EventTransformer(
    private val eventApi: AgentEventApi
) {
    fun start() {
        // Subscribe to FileCreated
        eventApi.subscribe<FileSystemEvent.FileCreated>(
            FileSystemEvent.FileCreated.EVENT_TYPE
        ) { event, _ ->
            // Transform and emit custom events
            if (shouldTransform(event)) {
                val customEvent = transformEvent(event)
                eventApi.publish(customEvent)
            }
        }
    }
}
```

### Historical Replay

Replay past file events:

```kotlin
// Replay all FileCreated events from the last hour
val oneHourAgo = Clock.System.now() - 1.hours
eventApi.replayEvents(
    since = oneHourAgo,
    eventType = FileSystemEvent.FileCreated.EVENT_TYPE
)
```

## Troubleshooting

### Events Not Firing

1. **Check receptor is running**: Ensure `receptor.start()` was called
2. **Verify file filter**: Check that your file passes the filter
3. **Check workspace path**: Ensure the path exists and is correct
4. **Look for errors**: Check logs for any exceptions

### Files Not Being Parsed

1. **Verify markdown format**: Ensure file follows the expected structure
2. **Check file extension**: Must be `.md` (case-insensitive)
3. **Verify content type detection**: Add explicit `Type` metadata field

### Events Not Reaching Agents

1. **Check subscriptions**: Ensure agents subscribed before events fired
2. **Verify event types**: Ensure subscription event type matches emitted type
3. **Check event persistence**: Verify EventRepository is working

## Next Steps

1. **Implement PM Agent Handler** - Create a proper integration with ProductManagerAgent
2. **Add GitHub Receptor** - Implement the GitHub issue polling receptor
3. **Create Task Tickets** - Wire PM Agent to create actual tickets in the system
4. **Add Validation** - Implement schema validation for markdown files
5. **Support Updates** - Handle file modifications as update events

## See Also

- [Event System Documentation](EventSystem.md)
- [Agent Architecture](AgentArchitecture.md)
- [CLI Documentation](../ampere-cli/README.md)
