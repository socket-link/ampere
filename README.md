<div align="center">

# ⚡ AMPERE

**Real-time cognition observability using collaborative AI agents.**

[![Maven Central](https://img.shields.io/maven-central/v/link.socket/ampere-core)](https://central.sonatype.com/artifact/link.socket/ampere-core)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Build](https://github.com/socket-link/ampere/actions/workflows/ci.yml/badge.svg)](https://github.com/socket-link/ampere/actions/workflows/ci.yml)

</div>

Ampere is a Kotlin Multiplatform framework where every agent decision emits a structured, queryable event – providing a cognitive architecture around AI actions that is observable by default, rather than just being instrumented after the fact.

When Ampere is used to run multiple agents in parallel, this open architecture allows for each agent to easily coordinate, negotiate, and react in real time to any environmental changes.

---

## Quick Start

> **Prerequisites:** Java 21+ (run `java -version` to check)

### Option 1: Standalone Tool (`ampere-cli`)

#### Build CLI
```bash
# <!--- Option 1: Installation from source ---> 
#
# 1. Clone the project
git clone https://github.com/socket-link/ampere.git
cd ampere

# 2. Configure LLM provider API keys in `local.properties`
cp ampere-cli/local.properties.example ampere-cli/local.properties
nano ampere-cli/local.properties

# 3. Build the CLI
./gradlew :ampere-cli:installDist

# 4. Add `ampere-cli` to your PATH for easy access from your project 
export PATH="$PATH:$(pwd)/ampere/ampere-cli"


# <!--- Option 2: Install prebuilt binaries --->
#
# Coming soon!
```

#### Configure Project
```bash
# 1. Copy the example `ampere.yaml` config into your <project> directory
cp ampere/ampere.example.yaml <project>/ampere.yaml

# 2. Make any necessary adjustments to the default agent configuration
nano <project>/ampere.yaml
```

**[All Configuration Options →](ampere-cli/README.md#configuration)**


#### Start Ampere

```bash
# Runs Ampere with a goal — this launches the TUI, and agents begin to communicate
cd <project> 
ampere run --goal "Add comprehensive documentation with interactive examples"
```

The dashboard then displays all agent cognition in real time: perception, recall, optimization, planning, execution, and coordination.

To change focus between panes in the CLI, press
- `d` for runtime overview
- `e` for event stream
- `m` for agent memory

Press  `?` for more options.

**[Full Usage Guide →](ampere-cli/README.md)**

### Option 2: Kotlin Multiplatform Library (`ampere-core`)

<details>
<summary><strong>Dependency Setup</strong></summary>

Add to your project:

```kotlin
// build.gradle.kts
dependencies {
    implementation("link.socket:ampere-core:0.1.1")
}
```

</details>
<details>
<summary><strong>Library Usage</strong></summary>

```kotlin
val team = AgentTeam.create {
    // Configure your AI provider
    config(AnthropicConfig(model = Claude.Sonnet4))

    // Add agents with personality traits
    agent(ProductManager) { personality { directness = 0.8 } }
    agent(Engineer) { personality { creativity = 0.7 } }
    agent(QATester)
}

// Assign a goal and observe the event stream
team.goal("Build a user authentication system")

team.events.collect { event ->
    when (event) {
        is Perceived -> println("${event.agent} noticed: ${event.signal}")
        is Recalled -> println("${event.agent} remembered: ${event.memory}")
        is Planned -> println("${event.agent} decided: ${event.plan}")
        is Executed -> println("${event.agent} did: ${event.action}")
        is Escalated -> println("${event.agent} needs help: ${event.reason}")
    }
}
```
</details>

---

## Why AMPERE?

Existing agent frameworks treat observability only as an afterthought – like when you attach LangSmith or Langfuse post-hoc to reconstruct AI behavior using traces.

AMPERE takes the opposite approach, where the cognitive architecture is emitting observable events at every single decision point.

| "Post-hoc" Observability                  | Ampere Observability                     |
|-------------------------------------------|------------------------------------------|
| Reconstruct behavior from traces          | Decisions are observable as they form    |
| Observe from outside the agent            | Cognition emits structured events        |
| Uncertainty hidden in token probabilities | Uncertainty surfaces and escalates       |
| Memory is an implementation detail        | Memory operations are first-class events |

When agent confidence for a plan drops below a configurable threshold, agents are able to **escalate to a human**, surfacing exactly what they're uncertain about.

This allows you to steer the agent toward an informed decision in real-time, rather than needing to debug opaque failures after the fact. 

## Cognition Primitives

| Concept       | Observable Surface         | Purpose                              |
|---------------|----------------------------|--------------------------------------|
| **Tickets**   | Goals and their lifecycle  | Track work from creation to close    |
| **Tasks**     | Discrete execution steps   | Trace every action an agent performs |
| **Plans**     | Structured decision logic  | Inspect reasoning before execution   |
| **Meetings**  | Inter-agent coordination   | Audit how agents negotiate and align |
| **Outcomes**  | Execution results          | Query historical performance         |
| **Knowledge** | Accumulated understanding  | Search what agents have learned      |

**[Core Concepts Guide →](docs/CORE_CONCEPTS.md)**

## PROPEL Cognitive Loop

During each timestep of the environment simulation, each agent executes its own independent cognitive cycle:

```
  1. Perceive  ──▶  2. Recall  ──▶  3. Optimize
  
         ▲                               │
         │                               ▼
        
      6. Loop  ◀──  5. Execute  ◀──  4. Plan
```

| # | Phase        | Operation                           | Emitted Events                         |
|---|--------------|-------------------------------------|----------------------------------------|
| 1 | **Perceive** | Ingest signals from the environment | `SignalReceived`, `PerceptionFormed`   |
| 2 | **Recall**   | Query relevant memory and context   | `MemoryQueried`, `ContextAssembled`    |
| 3 | **Optimize** | Prioritize competing objectives     | `ObjectivesRanked`, `ConfidenceScored` |
| 4 | **Plan**     | Select and structure actions        | `PlanCreated`, `TasksDecomposed`       |
| 5 | **Execute**  | Carry out the plan                  | `ActionTaken`, `ResultObserved`        |
| 6 | **Loop**     | Evaluate results, re-enter cycle    | `OutcomeEvaluated`, `CycleRestarted`   |

Every phase transition is emitted as an event, ensuring every action inside an agent can be audited and traced.

**[Full Cognitive Lifecycle →](docs/AGENT_LIFECYCLE.md)**

## Full Documentation

| Guide                                      | Description                         |
|--------------------------------------------|-------------------------------------|
| [CLI Reference](ampere-cli/README.md)      | Command-line tools                  |
| [Core Concepts](docs/CORE_CONCEPTS.md)     | The observable cognition primitives |
| [Agent Lifecycle](docs/AGENT_LIFECYCLE.md) | The PROPEL loop in detail           |
| [Architecture](docs/ARCS.md)               | System architecture overview        |
| [Contributing](CONTRIBUTING.md)            | How to contribute to the project    |

---

## License

Apache 2.0 — see [LICENSE.txt](LICENSE.txt) for more details.

Copyright 2026 Miley Chandonnet, Stedfast Softworks LLC
