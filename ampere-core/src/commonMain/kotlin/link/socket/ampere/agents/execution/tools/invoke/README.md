# ToolInvoker - Motor Neuron for Tool Execution

The `ToolInvoker` provides a lightweight abstraction for executing individual tools with lifecycle management, validation, and observability.

## Overview

Unlike the system-level `Executor` interface (which coordinates multiple tools and execution strategies), `ToolInvoker` is a simple wrapper around a single tool that provides:

- **Type-safe execution** with compile-time validation
- **Lifecycle event emission** for observability (ToolExecutionStarted, ToolExecutionCompleted)
- **Error isolation** - exceptions are caught and transformed to results
- **Timing measurement** - execution duration is automatically captured
- **Optional event publishing** - works with or without event API

## Usage

### Basic Invocation

```kotlin
// Create a tool
val tool = FunctionTool<ExecutionContext.NoChanges>(
    id = "hello-world",
    name = "Hello World",
    description = "Returns a greeting",
    requiredAgentAutonomy = AgentActionAutonomy.FULL,
    executionFunction = { request ->
        Outcome.Success("Hello, ${request.context.instructions}!")
    }
)

// Create invoker (without events)
val invoker = ToolInvoker(tool)

// Create execution request
val request = ExecutionRequest(
    context = ExecutionContext.NoChanges(
        executorId = "my-executor",
        ticket = myTicket,
        task = myTask,
        instructions = "World"
    ),
    constraints = ExecutionConstraints(
        maxDurationSeconds = 60,
        allowNetworkAccess = false,
        allowFileSystemAccess = false
    )
)

// Invoke the tool
val result = invoker.invoke(request)

// Handle result
when (result) {
    is ToolInvocationResult.Success -> {
        println("Success: ${result.outcome.value}")
        println("Duration: ${result.duration}")
    }
    is ToolInvocationResult.Failed -> {
        println("Failed: ${result.error}")
        result.exception?.printStackTrace()
    }
    is ToolInvocationResult.Blank -> {
        println("No operation")
    }
}
```

### With Event Publishing

```kotlin
// Create event API for the agent
val eventApi = AgentEventApi(
    agentId = "my-agent",
    eventRepository = eventRepo,
    eventSerialBus = eventBus
)

// Create invoker with event API
val invoker = ToolInvoker(tool, eventApi)

// Invoke - events will be automatically emitted
val result = invoker.invoke(request)

// Events emitted:
// 1. ToolEvent.ToolExecutionStarted - before execution
// 2. ToolEvent.ToolExecutionCompleted - after execution (with success/failure/duration metadata)
```

### Code Context Tools

```kotlin
val writeCodeTool = FunctionTool<ExecutionContext.Code.WriteCode>(
    id = "write-code",
    name = "Write Code",
    description = "Writes code to files",
    requiredAgentAutonomy = AgentActionAutonomy.SUPERVISED,
    executionFunction = { request ->
        val files = request.context.instructionsPerFilePath
        // Write files...
        Outcome.Success("Wrote ${files.size} files")
    }
)

val invoker = ToolInvoker(writeCodeTool)

val request = ExecutionRequest(
    context = ExecutionContext.Code.WriteCode(
        executorId = "code-writer",
        ticket = ticket,
        task = task,
        instructions = "Implement the feature",
        workspace = ExecutionWorkspace("/path/to/workspace"),
        instructionsPerFilePath = listOf(
            "src/Main.kt" to "fun main() { println(\"Hello\") }"
        )
    ),
    constraints = ExecutionConstraints(
        maxDurationSeconds = 300,
        allowNetworkAccess = false,
        allowFileSystemAccess = true
    )
)

val result = invoker.invoke(request)
```

### Pre-flight Validation

```kotlin
// Validate request before invoking (optional - invoke does this automatically)
val validationResult = invoker.validate(request)

when (validationResult) {
    is ValidationResult.Valid -> {
        // Request is valid, safe to invoke
        val result = invoker.invoke(request)
    }
    is ValidationResult.Invalid -> {
        // Request has validation errors
        println("Validation errors: ${validationResult.errors}")
    }
}
```

## Architecture Notes

### Relationship to System-Level Executor

The `Executor` interface (implemented by `McpExecutor`, `CodeExecutor`, etc.) is a system-level abstraction that:
- Coordinates multiple tools
- Returns `Flow<ExecutionStatus>` for streaming progress
- Handles health checks and capabilities
- Manages connections to external systems (MCP servers, etc.)

The `ToolInvoker` is a simpler, tool-level abstraction that:
- Wraps a single tool
- Returns `ToolInvocationResult` directly (no streaming)
- Focuses on execution lifecycle and observability
- Used by agents directly in their cognitive loop

Think of `Executor` as the "execution system" and `ToolInvoker` as the "tool wrapper".

### Use in Agent Cognitive Loop

The `ToolInvoker` is designed for agents to use in their `runLLMToExecuteTool` implementation:

```kotlin
class MyAgent(
    private val toolInvoker: ToolInvoker<ExecutionContext.Code.WriteCode>,
    private val eventApi: AgentEventApi
) : AutonomousAgent<AgentState>() {

    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
        { tool, request ->
            // Use ToolInvoker to execute the tool
            val invoker = ToolInvoker(tool as Tool<ExecutionContext>, eventApi)
            val result = invoker.invoke(request as ExecutionRequest<ExecutionContext>)

            // Transform to ExecutionOutcome
            when (result) {
                is ToolInvocationResult.Success -> {
                    // Create appropriate ExecutionOutcome.Success based on tool type
                    // ...
                }
                is ToolInvocationResult.Failed -> {
                    // Create ExecutionOutcome.Failure
                    // ...
                }
                else -> ExecutionOutcome.Blank
            }
        }
}
```

## Event Schema

### ToolEvent.ToolExecutionStarted

Emitted before tool execution begins.

```kotlin
data class ToolExecutionStarted(
    eventId: EventId,
    timestamp: Instant,
    eventSource: EventSource,
    urgency: Urgency,
    invocationId: String,            // Unique per invocation
    toolId: ToolId,
    toolName: String
) : ToolEvent
```

### ToolEvent.ToolExecutionCompleted

Emitted after tool execution finishes (success or failure).

```kotlin
data class ToolExecutionCompleted(
    eventId: EventId,
    timestamp: Instant,
    eventSource: EventSource,
    urgency: Urgency,
    invocationId: String,            // Matches Started event
    toolId: ToolId,
    toolName: String,
    success: Boolean,                // true if succeeded, false if failed
    durationMs: Long,                // Execution duration in milliseconds
    errorMessage: String? = null     // Error message if failed, null if succeeded
) : ToolEvent
```

## Testing

See `ToolInvokerTest.kt` and `ToolInvokerEventsTest.kt` for comprehensive test coverage including:

- Basic invocation and result transformation
- Validation logic
- Error handling and isolation
- Timing measurement
- Multiple invokers without interference
- Event emission verification
- Different context types (NoChanges, Code.WriteCode, etc.)
