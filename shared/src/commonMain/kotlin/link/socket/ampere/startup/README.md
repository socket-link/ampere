# Ampere Startup Integration

This directory contains the startup initialization logic for the Ampere agent system.

## Quick Start

To integrate tool initialization into your application startup, follow these steps:

### 1. Add imports to your App.kt or main entry point

```kotlin
import link.socket.ampere.agents.tools.registry.ToolRegistry
import link.socket.ampere.db.Database
import link.socket.ampere.startup.initializeAmpere
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
```

### 2. Initialize Ampere at application startup

In your main `App` composable or application initialization code:

```kotlin
@Composable
fun App(
    databaseDriver: SqlDriver,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val json = remember { DEFAULT_JSON }

    // Create database from driver
    val database = remember(databaseDriver) {
        Database(databaseDriver)
    }

    // Tool registry state
    val toolRegistry = remember { mutableStateOf<ToolRegistry?>(null) }

    // Initialize Ampere system on startup
    LaunchedEffect(database) {
        initializeAmpere(
            database = database,
            json = json,
            scope = scope
        ).onSuccess { result ->
            toolRegistry.value = result.registry
            println("Ampere initialized successfully with ${result.toolInitialization.successfulRegistrations} tools")
        }.onFailure { error ->
            println("Failed to initialize Ampere: ${error.message}")
        }
    }

    // Rest of your app UI...
}
```

### 3. Alternative: Initialize before UI

For non-Compose applications (CLI, server, etc.):

```kotlin
fun main() = runBlocking {
    val driver = createJvmDriver()
    val database = Database(driver)
    val scope = CoroutineScope(Dispatchers.Default)
    val json = Json { prettyPrint = true }

    // Initialize Ampere
    val result = initializeAmpere(
        database = database,
        json = json,
        scope = scope
    ).getOrThrow()

    println("Tool registry ready with ${result.toolInitialization.successfulRegistrations} tools")

    // Access the registry
    val registry = result.registry
    val allTools = registry.getAllTools()

    // Your application logic...
}
```

## What Gets Initialized

The `initializeAmpere()` function performs these steps:

1. **Creates ToolRegistry** - The central registry for all tools (function and MCP)
2. **Registers Local Tools** - Discovers and registers all local FunctionTools:
   - `write_code` - Write or modify source code files
   - `read_code` - Read source code files
   - `ask_human` - Escalate decisions to human operators
   - `create_ticket` - Create tasks in the issue tracking system
   - `run_tests` - Execute test suites
3. **Emits Events** - Publishes ToolDiscoveryComplete event for observability
4. **Returns Registry** - Provides the ToolRegistry for use by agents

## Initialization Result

The `AmpereStartupResult` contains:

```kotlin
data class AmpereStartupResult(
    val registry: ToolRegistry,           // The initialized tool registry
    val toolInitialization: ToolInitializationResult  // Initialization statistics
)

data class ToolInitializationResult(
    val totalTools: Int,                  // Number of tools discovered
    val successfulRegistrations: Int,     // Number successfully registered
    val failedRegistrations: Int,         // Number that failed
    val failures: List<ToolRegistrationFailure>  // Details of failures
)
```

## Error Handling

The initialization is designed to be resilient:

- **Partial Success**: If some tools fail to register, the system continues with the tools that succeeded
- **Logging**: All registration successes and failures are logged with details
- **Result Type**: Returns `Result<AmpereStartupResult>` for explicit error handling

Example with full error handling:

```kotlin
LaunchedEffect(database) {
    initializeAmpere(database, json, scope)
        .onSuccess { result ->
            toolRegistry.value = result.registry

            if (result.isFullSuccess) {
                println("✓ All tools initialized successfully")
            } else {
                println("⚠ Partial initialization: ${result.toolInitialization.failedRegistrations} failures")
                result.toolInitialization.failures.forEach { failure ->
                    println("  - ${failure.toolName}: ${failure.error}")
                }
            }
        }
        .onFailure { error ->
            println("✗ Critical failure: ${error.message}")
            // Decide whether to continue without tools or exit
        }
}
```

## Using the Tool Registry

Once initialized, use the registry to:

### Query available tools
```kotlin
val allTools = registry.getAllTools()
val writeCodeTool = registry.getTool("write_code")
```

### Find tools by autonomy level
```kotlin
val fullyAutonomousTools = registry.findToolsByAutonomy(
    AgentActionAutonomy.FULLY_AUTONOMOUS
)
```

### Find tools by capability
```kotlin
val codeTools = registry.findToolsByCapability("code")
val githubTools = registry.findToolsByCapability("github")
```

## Next Steps

After initialization:

1. **Pass the registry to agents** - Agents need access to discover and execute tools
2. **MCP Integration** (Future) - Task AMP-203.4 will add MCP server tool discovery
3. **Dynamic Tool Loading** - Tools can be registered/unregistered at runtime

## See Also

- `ToolInitializer.kt` - Local tool factory and initialization
- `ToolRegistry.kt` - Tool registration and discovery service
- `Tool.kt` - Sealed interface for FunctionTool and McpTool
