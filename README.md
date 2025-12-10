[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# ⚡ AMPERE

**Multi-agent team orchestration, compatible with the existing Agentic AI Foundation ecosystem**

The [Agentic AI Foundation](https://aaif.io) has published standards for how AI agents should connect to tools (MCP), specify permissions (Agents.md), and implement basic behaviors (Goose).

**But something's missing: how will the AI agents coordinate with *each other*?**

AMPERE answers this question by providing a simple framework for managing collaboration within a group of AI agents.

The runtime pattern that AMPERE uses for coordinating agents is inspired by biological systems:
- Event-driven runtime that manages the LLM context by simulating interactions and decision-making within a PROPEL loop
- Asynchronous event emissions are broadcast across the agents' environment, allowing for mass coordination across different sections of the system
- Persistent memory with chunking and consolidation allows for reflection on previous task outcomes, allowing agents to improve their behavior
- Agents' ability to change their future behavior based on changes in stored knowledge can allow for emergent agent behavior 
- A Kotlin Multiplatform library built for the JVM ecosystem, compatible with Android, iOS, and desktop applications

---

## The Missing Layer

| AAIF Provides | AMPERE Provides |
|---------------|-----------------|
| Agent ↔ Tool connections (MCP) | Agent ↔ Agent coordination |
| Permission specifications (Agents.md) | Uncertainty escalation to humans |
| Reference agent implementation (Goose) | Emergent multi-agent orchestration |
| Python/TypeScript SDKs | Kotlin Multiplatform (JVM, Android, iOS, Desktop) |

AMPERE provides an AI behavior orchestration layer that makes AAIF-compliant agents *intelligent*.

---

## Why Biology?

Traditional agent frameworks use request-response patterns: Agent A calls Agent B, waits for response, proceeds. 

This synchronous nature of communication creates bottlenecks, tightly couples agents, and only provides fragile 2-way coordination patterns.

**But biological systems solved multi-agent coordination billions of years ago.** 

Cells don't make synchronous RPC calls—they emit signals and respond to environmental state. Neurons don't poll each other—they react to electrochemical gradients. Immune systems don't have a central orchestrator—complex behavior emerges from simple local rules.

AMPERE provides tools to LLMs that allow them to run a simulated runtime environment, where agents can act and communicate asynchronously:

- **Event-driven coordination** — Agents emit signals; other agents subscribe and react. No request-response chains
- **Persistent memory** — Execution outcomes consolidate into searchable knowledge. Irrelevant information fades
- **Uncertainty escalation** — Agents recognize when they're uncertain and escalate to humans instead of hallucinating
- **Emergent behavior** — Complex team coordination arises from simple individual agent rules

---

## Quick Start

> **Current Development Status:**
>
> After being in active development for over 2 years, AMPERE is now available as an Alpha release.
>
> **Note:** Some of the code examples below show the planned high-level API that is currently in development. For accurate details of the current API implementation, see the [CLI Guide](ampere-cli/README.md) and [CLAUDE.md](CLAUDE.md).
>
> **We're looking for collaborators to help build AMPERE into a production-ready framework.**
>
>[Report issues →](https://github.com/socket-link/ampere/issues)

To get started, create an agent team and give them a goal:

```kotlin
// 1. Configure your AI provider
val aiConfig = AnthropicConfig(
    apiKey = "your-api-key",
    model = Claude.Sonnet4
)

// 2. Create an agent team
val team = AgentTeam.create {
    agent(ProductManager) { personality { directness = 0.8 } }
    agent(Engineer) { personality { creativity = 0.7 } }
    agent(QATester)
}

// 3. Give them a goal
team.pursue("Build a user authentication system with OAuth2 support")

// 4. Watch them work
team.events.collect { event ->
    when (event) {
        is TicketCreated -> println("📋 ${event.ticket.title}")
        is TaskCompleted -> println("✅ ${event.task.name}")
        is EscalationRequested -> println("🚨 Human input needed: ${event.reason}")
    }
}
```

**What will happen next:**
1. ProductManager breaks the goal into tickets
2. Engineer picks up tickets, creates execution plans
3. Agents coordinate through meetings when blocked
4. QATester validates outputs
5. Learnings persist to knowledge base for future work
6. Uncertain decisions escalate to you if necessary

---

## The PROPEL Runtime Loop

After being initialized, each agent runs autonomously in their own loop using a biologically-inspired cognitive cycle:
```
┌────────────────────────────────────────────────────────┐
│                                                        │
│   ┌──────────┐    ┌──────────┐    ┌──────────┐         │
│   │ PERCEIVE │───▶│  RECALL  │───▶│ OPTIMIZE │         │
│   │  signals │    │  memory  │    │  select  │         │
│   └──────────┘    └──────────┘    └──────────┘         │
│        ▲                               │               │
│        │                               ▼               │
│   ┌──────────┐                   ┌──────────┐          │
│   │   LOOP   │◀──────────────────│   PLAN   │          │
│   │  repeat  │                   │  decide  │          │
│   └──────────┘                   └──────────┘          │
│        ▲                               │               │
│        │         ┌──────────┐          │               │
│        └─────────│ EXECUTE  │◀─────────┘               │
│                  │   act    │                          │
│                  └──────────┘                          │
│                                                        │
└────────────────────────────────────────────────────────┘
```

This cognition cycle gives agents the flexibility to adapt their behavior over time to adapt based on changes in the environment.

**[→ Detailed Agent Lifecycle](docs/AGENT_LIFECYCLE.md)**

---

## Core Concepts

AMPERE controls the agents' behavior by simulating a work environment with six primitive types:

| Concept | Biological Analog | Purpose |
|---------|-------------------|---------|
| **Tickets** | Goals/Stimuli | Units of work with lifecycle tracking |
| **Tasks** | Motor actions | Individual execution steps |
| **Plans** | Neural pathways | Bridge between goals and actions |
| **Meetings** | Synaptic convergence | Coordination points between agents |
| **Outcomes** | Episodic memory | Execution results that inform future behavior |
| **Knowledge** | Semantic memory | Consolidated learnings searchable by similarity |

These concepts allow AMPERE to run a simulation of a work environment that mirrors how human teams operate in a company, which provides a flexible and adaptable framework for coordinated agent behavior.

**[→ Complete Core Concepts Guide](docs/CORE_CONCEPTS.md)**

---

## Platform Support

Being a Kotlin Multiplatform library allows AMPERE to run on every platform that the JVM runs on:

| Platform | Status | Use Case |
|----------|--------|----------|
| **JVM** | ✅ Stable | Server-side agent orchestration |
| **Android** | ✅ Stable | On-device agent coordination |
| **Desktop** | ✅ Stable | Local-first agent development |
| **iOS** | 🔄 Beta | Cross-platform mobile apps |
| **CLI** | ✅ Stable | Observability and debugging |

---

## AI Model Provider Support

AMPERE's runtime is model-agnostic, and the SDK allows for model provider configuration with automatic failover:
```kotlin
val config = MultiProviderConfig(
    primary = AnthropicConfig(model = Claude.Opus_4_5),
    fallback = listOf(
        GoogleConfig(model = Gemini.Pro_3),
        OpenAIConfig(model = OpenAI.GPT_5_1)
    )
)
```

**Currently supported LLM providers:** 
- Anthropic (Claude 4.x, 3.x)
- Google (Gemini 3, 2.x)
- OpenAI (GPT-5.x, 4.x, o3/o4)

---

## Observability

Use the CLI to watch agents work together in real-time:
```bash
# Install CLI
./gradlew :ampere-cli:installJvmDist

# Watch event stream
./ampere watch

# Query execution history  
./ampere outcomes search "authentication"

# View agent status
./ampere status
```
```
📋 TicketCreated    [pm-agent]        14:23:01  FEAT-123: Add user authentication
✅ TicketAssigned   [pm-agent]        14:23:15  FEAT-123 → engineer-agent  
🔨 StatusChanged    [engineer-agent]  14:23:45  FEAT-123: Ready → InProgress
💭 MeetingStarted   [engineer-agent]  14:25:00  Clarifying OAuth2 requirements
✅ TaskCompleted    [engineer-agent]  14:27:33  Create User model - SUCCESS
🧠 KnowledgeStored  [engineer-agent]  14:27:35  "OAuth2 requires PKCE for mobile"
```

**[→ Complete CLI Guide](ampere-cli/README.md)**

---

## Installation

> **Note:** The library is just nearing its initial release, so it is not yet available on Maven Central.
> 
> For now the library can be built locally using `./gradlew publishToMavenLocal` to test the functionality, and an initial release will be published to Maven Central shortly.

**Gradle (Kotlin DSL):**
```kotlin
implementation("link.socket.ampere:ampere-core:0.1.0")
```

**Gradle (Groovy):**
```groovy
implementation 'link.socket.ampere:ampere-core:0.1.0'
```

---

## Roadmap for AAIF Compatibility

AMPERE is positioning itself as an enhancement to the initial AAIF standards:

- [x] Event-driven agent coordination 
- [x] Persistent memory and knowledge systems
- [x] Uncertainty escalation patterns
- [x] Native tool discovery via Model Context Protocol
- [ ] **Agents.md compliance** — Permission specification support
- [ ] **Goose interop** — Orchestrate Goose agents alongside native AMPERE agents
- [ ] **Protocol proposals** — Contributing agent coordination standards to AAIF

---

## Contributing

AMPERE is built on the thesis that biological patterns provide superior agent coordination. We're looking for collaborators interested in:

- **Event-driven architectures** — Kafka, Akka, or reactive systems background
- **Kotlin Multiplatform** — Cross-platform development expertise
- **Agent systems** — Experience with LangChain, AutoGen, CrewAI, or similar
- **Biological modeling** — Background in systems biology, neuroscience, or emergence

**[→ Contributing Guide](CONTRIBUTING.md)**

---

## Documentation

| Guide | Description |
|-------|-------------|
| [Core Concepts](docs/CORE_CONCEPTS.md) | Tickets, Tasks, Plans, Meetings, Outcomes, Knowledge |
| [Agent Lifecycle](docs/AGENT_LIFECYCLE.md) | The PROPEL loop with detailed examples |
| [CLI Reference](ampere-cli/README.md) | Complete command documentation |
| [CLAUDE.md](CLAUDE.md) | Guide for AI-assisted development |

---

## License

```
Copyright 2025 Miley Chandonnet, Stedfast Softworks LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
