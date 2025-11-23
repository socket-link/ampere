# Agent System Overview

This document describes the minimal autonomous agent system added to Ampere, including core interfaces, tools, and an example workflow using the CodeWriterAgent.

## Interfaces

- MinimalAutonomousAgent
  - perceive(): Context — observe current state
  - reason(): Plan — derive a plan from perceived context and task
  - plan(): Plan — cache and/or return the current plan
  - act(): Outcome — execute one step from the plan
  - signal(): Message? — communicate uncertainties or ask for approval

All return types are immutable data classes in link.socket.ampere.agents.core.AgentTypes.

## Autonomy Levels

AutonomyLevel calibrates trust for tool execution:
- ASK_BEFORE_ACTION — agent proposes and waits for approval
- ACT_WITH_NOTIFICATION — agent acts and notifies
- FULLY_AUTONOMOUS — agent acts independently and logs
- SELF_CORRECTING — agent acts and self‑reviews

## Tools

All tools implement the Tool interface and declare requiredAutonomyLevel.

Foundation tools included:
- AskHumanTool — routes questions to a human interface and returns the response.
- ReadCodebaseTool — reads files or directories within a sandboxed root.
- WriteCodeFileTool — writes a single file to a base directory (JVM impl provided).
- RunTestsTool — runs Gradle tests at a project root (JVM impl provided).

Tools validate parameters, handle errors gracefully, and return Outcome.

## CodeWriterAgent

A minimal concrete agent that:
- Reads a task string via setTask()
- Produces a simple plan for recognized patterns (e.g., "Add a new tool called …")
- Emits a signal asking for approval when required
- Executes steps deterministically (placeholder execution for now)

### Usage Example

```kotlin
val tempDir = Files.createTempDirectory("agent_test").toFile()
val tools = mapOf(
    "ask_human" to AskHumanTool { q -> "Approved: $q" },
    "write_code_file" to WriteCodeFileTool(tempDir.absolutePath),
    "read_codebase" to ReadCodebaseTool(tempDir.absolutePath),
    "run_tests" to RunTestsTool(tempDir.absolutePath)
)

val agent = CodeWriterAgent(tools)
agent.setTask("Add a new tool called TestTool")

val ctx = agent.perceive()
val plan = agent.plan()
val signal = agent.signal() // MessageSeverity.QUESTION
val outcome = agent.act()   // Executes first step
```

## Running Tests

- JVM tests only for shared module:
  - ./gradlew :shared:jvmTest

## Notes

- The current CodeWriterAgent is deterministic and does not actually generate code; it scaffolds the lifecycle and tool wiring.
- Future milestones can expand tool selection and execution mapping, add richer reasoning, and enable non-JVM targets for the expect/actual tools.
