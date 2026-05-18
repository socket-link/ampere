---json
{
  "type": "project",
  "id": "ampere",
  "name": "Project:ampere",
  "projectId": "ampere",
  "repositoryRoot": "${env:AMPERE_ROOT:-${env:PWD:-.}}"
}
---

## Project Description

AMPERE is a Kotlin Multiplatform framework for building autonomous AI agent systems.

## Purpose
AMPERE enables the creation of collaborative AI agent teams that can:
- Communicate through typed events and messages
- Maintain long-term memory and learn from experiences
- Execute tasks autonomously with appropriate tool access
- Coordinate through meetings and tickets

## Key Components
- **Agents**: Autonomous entities with cognitive cycles (PERCEIVE → PLAN → EXECUTE → LEARN)
- **Events**: Typed messages for agent communication (MessagePosted, TicketAssigned, etc.)
- **Sparks**: Cognitive differentiation layers that specialize agent behavior
- **Memory**: Knowledge persistence and recall for experiential learning

## Architecture
- `ampere-core`: Multiplatform core library with agent infrastructure
- `ampere-cli`: JVM command-line interface for running agents
- Kotlin Multiplatform targeting JVM and Android

## The Spark System
Agents use a "cellular differentiation" model where a single SparkBasedAgent class
specializes through accumulated Spark layers:
1. CognitiveAffinity: Base thinking approach (ANALYTICAL, EXPLORATORY, OPERATIONAL, INTEGRATIVE)
2. ProjectSpark: Project context and conventions
3. Declarative role spark: Capability focus (Code, Research, Operations, Planning)
4. CoordinationSpark: Handoff and coordination focus (optional, when needed)
5. TaskSpark: Current task context (applied/removed with task lifecycle)

## Project Conventions

## Kotlin Style
- Follow Kotlin official coding conventions
- Use data classes for immutable value types
- Prefer sealed classes/interfaces for domain modeling
- Use kotlinx.serialization for JSON handling
- Use kotlinx.coroutines for async operations

## Package Structure
- `agents.definition`: Agent class implementations
- `agents.domain.cognition`: Spark system and cognitive types
- `agents.domain.event`: Event types for agent communication
- `agents.domain.memory`: Knowledge and memory persistence
- `agents.events`: Event bus and subscription infrastructure
- `agents.execution`: Tool execution and task running

## Testing
- Write tests in `commonTest` for platform-independent logic
- Use `jvmTest` for JVM-specific tests
- Follow existing test patterns with `@Test` annotation
- Use descriptive test names that explain what's being tested

## Documentation
- Use KDoc for public APIs
- Include examples in documentation where helpful
- Keep comments focused on "why" rather than "what"

## Event Handling
- All agent events should be serializable with kotlinx.serialization
- Use EventSource to track event origin
- Include timestamp in all events
- Prefer immutable event data classes
