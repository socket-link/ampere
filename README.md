<div align="center">

# ⚡ AMPERE

**Watch your AI agents think.**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Build](https://github.com/socket-link/ampere/actions/workflows/ci.yml/badge.svg)](https://github.com/socket-link/ampere/actions/workflows/ci.yml)

</div>

Ampere is a Kotlin Multiplatform framework for multi-agent AI systems where observability is built in, not bolted on. Every agent decision emits a structured event you can watch, query, and act on in real time.

<!-- TODO: Replace with recorded demo GIF (see assets/demos/RECORDING.md) -->
> **What the event stream looks like:**
```
📋 TicketCreated    [pm-agent]        14:23:01  FEAT-123: Add user authentication
✅ TicketAssigned   [pm-agent]        14:23:15  FEAT-123 → engineer-agent
🔨 StatusChanged    [engineer-agent]  14:23:45  FEAT-123: Ready → InProgress
⚠️ Uncertain        [engineer-agent]  14:25:12  "Should we use PKCE or implicit flow?"
🧠 Recalled         [engineer-agent]  14:25:14  Previous: "PKCE required for mobile"
✅ TaskCompleted    [engineer-agent]  14:27:33  Create User model - SUCCESS
```

---

## Quick Start

> **Prerequisites:** Java 21+ (`java -version` to check)

```bash
# Clone and build
git clone https://github.com/socket-link/ampere.git
cd ampere
./gradlew :ampere-cli:installDist

# Copy the example config and add your API key
cp ampere.example.yaml ampere.yaml

# Run with a goal — launches the TUI and starts agents
./ampere-cli/ampere run --goal "Build a user authentication system"
```

The 3-column TUI dashboard shows agent perception, planning, and decisions in real time. Use `d` for dashboard mode, `e` for event stream, `m` for memory operations, or `?` for help.

> **[CLI Guide](ampere-cli/README.md)** · **[Configuration](ampere-cli/README.md#configuration)** · **[Contributing](CONTRIBUTING.md)**

---

<details>
<summary><strong>Kotlin DSL (library usage)</strong></summary>

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
team.pursue("Build a user authentication system")

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

Add to your project:

```kotlin
// build.gradle.kts
dependencies {
    implementation("link.socket:ampere:0.1.0")
}
```

</details>

---

## Why Ampere?

Agent frameworks like LangChain and CrewAI treat observability as an add-on — you bolt on LangSmith or Langfuse after building the agent and reconstruct behavior from traces after the fact.

Ampere takes the opposite approach. The cognitive architecture emits observable events as agents work:

| External Observability                | Built-in Observability             |
|---------------------------------------|------------------------------------|
| Reconstruct behavior from traces      | Watch decisions as they form       |
| Observe from outside the agent        | Cognition emits events directly    |
| Uncertainty often hidden              | Uncertainty surfaces and escalates |
| Memory is implementation detail       | Memory operations are visible      |

When an agent's confidence drops below a threshold, it **escalates to a human** rather than guessing — explaining its uncertainty in terms you can evaluate, then incorporating your judgment into subsequent reasoning.

**[→ Agent Lifecycle](docs/AGENT_LIFECYCLE.md)** · **[→ Core Concepts](docs/CORE_CONCEPTS.md)**

---

## The PROPEL Cognitive Loop

Each agent executes an observable cognitive cycle:

```
  PERCEIVE ──▶ RECALL ──▶ OPTIMIZE
      ▲                       │
      │                       ▼
    LOOP ◀──── EXECUTE ◀──── PLAN
```

**Perceive** — "What's happening?" · **Recall** — "What do I know?" · **Optimize** — "What matters most?"
**Plan** — "What should I do?" · **Execute** — "Do it." · **Loop** — "What next?"

Every phase emits events that describe the agent's current cognitive state. **[→ Details](docs/AGENT_LIFECYCLE.md)**

---

## Core Concepts

| Concept       | What You See           | Purpose                           |
|---------------|------------------------|-----------------------------------|
| **Tickets**   | Goals being pursued    | Work units with visible lifecycle |
| **Tasks**     | Actions being taken    | Individual steps you can trace    |
| **Plans**     | Decisions being formed | The reasoning you can follow      |
| **Meetings**  | Coordination happening | Agents explaining to each other   |
| **Outcomes**  | Results being recorded | Execution history you can query   |
| **Knowledge** | Understanding forming  | Learnings you can search          |

**[→ Core Concepts Guide](docs/CORE_CONCEPTS.md)**

---

## Platform Support

| Platform    | Status   | Use Case                  |
|-------------|----------|---------------------------|
| **JVM**     | ✅ Stable | Server-side orchestration |
| **Android** | ✅ Stable | On-device agents          |
| **Desktop** | ✅ Stable | Local development         |
| **iOS**     | 🔄 Beta  | Cross-platform apps       |
| **CLI**     | ✅ Stable | Monitoring and management |

## Model Support

Model-agnostic with automatic failover:

```kotlin
val config = AnthropicConfig(model = Claude.Sonnet4)
    .withBackup(OpenAIConfig(model = GPT.GPT4_1))
    .withBackup(GeminiConfig(model = Gemini.Flash2_5))
```

**Supported providers:** Anthropic (Claude 4.x, 3.x) · Google (Gemini 3, 2.x) · OpenAI (GPT-5.x, 4.x, o3/o4)

---

## Contributing

> **Alpha — [we're looking for collaborators](https://github.com/socket-link/ampere/issues)**

- **Observability systems** — Agent monitoring and tracing
- **Kotlin Multiplatform** — Cross-platform development
- **Agent architectures** — LangChain, AutoGen, CrewAI experience
- **Cognitive science** — How humans understand complex systems

**[→ Contributing Guide](CONTRIBUTING.md)**

## Documentation

| Guide                                      | Description                   |
|--------------------------------------------|-------------------------------|
| [CLI Reference](ampere-cli/README.md)      | Command-line tools            |
| [Core Concepts](docs/CORE_CONCEPTS.md)     | The six observable primitives |
| [Agent Lifecycle](docs/AGENT_LIFECYCLE.md) | The PROPEL loop in detail     |
| [Architecture](docs/ARCS.md)              | System architecture overview  |

## License

Apache 2.0 — see [LICENSE.txt](LICENSE.txt) for details.

Copyright 2026 Miley Chandonnet, Stedfast Softworks LLC
