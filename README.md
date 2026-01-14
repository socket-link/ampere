[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# ⚡ AMPERE

**A transparent multi-agent coordination framework.**

AMPERE is a Kotlin Multiplatform library for building AI agent systems with built-in observability. Rather than treating transparency as an add-on, the framework surfaces agent cognition—perception, memory recall, decision-making, and uncertainty—as first-class observable events.

The framework implements **Computer-Human Interaction (CHI)**: a standard for agents to explain their reasoning in terms that a human can understand and evaluate.

```
📋 TicketCreated    [pm-agent]        14:23:01  FEAT-123: Add user authentication
✅ TicketAssigned   [pm-agent]        14:23:15  FEAT-123 → engineer-agent  
🔨 StatusChanged    [engineer-agent]  14:23:45  FEAT-123: Ready → InProgress
💭 MeetingStarted   [engineer-agent]  14:25:00  Clarifying OAuth2 requirements
⚠️ Uncertain        [engineer-agent]  14:25:12  "Should we use PKCE or implicit flow?"
🧠 Recalled         [engineer-agent]  14:25:14  Previous: "PKCE required for mobile"
✅ TaskCompleted    [engineer-agent]  14:27:33  Create User model - SUCCESS
🧠 KnowledgeStored  [engineer-agent]  14:27:35  "OAuth2 requires PKCE for mobile"
```

The event stream provides visibility into agent decision-making as it occurs.

---

## Architectural Transparency

Many agent frameworks treat observability as external tooling—LangSmith, Langfuse, or AgentOps are added after the agent is built. 

This approach reconstructs agent behavior from traces after the fact.

AMPERE takes a different approach: the cognitive architecture is designed for legibility from the start.

| External Observability                | Built-in Observability             |
|---------------------------------------|------------------------------------|
| Reconstruct behavior from traces      | Watch decisions as they form       |
| Observe from outside the agent        | Cognition emits events directly    |
| Uncertainty often hidden              | Uncertainty surfaces and escalates |
| Memory is implementation detail       | Memory operations are visible      |

---

## The PROPEL Cognitive Loop

Each agent executes an observable cognitive cycle consisting of six phases:

```
┌────────────────────────────────────────────────────────┐
│                     VISIBLE COGNITION                  │
│                                                        │
│   ┌──────────┐    ┌──────────┐    ┌──────────┐         │
│   │ PERCEIVE │───▶│  RECALL  │───▶│ OPTIMIZE │         │
│   │  "What's │    │  "What   │    │  "What   │         │
│   │  happening?"  │  do I    │    │  matters │         │
│   │          │    │  know?"  │    │  most?"  │         │
│   └──────────┘    └──────────┘    └──────────┘         │
│        ▲                               │               │
│        │                               ▼               │
│   ┌──────────┐                   ┌──────────┐          │
│   │   LOOP   │◀──────────────────│   PLAN   │          │
│   │  "What   │                   │  "What   │          │
│   │  next?"  │                   │  should  │          │
│   └──────────┘                   │  I do?"  │          │
│        ▲                         └──────────┘          │
│        │         ┌──────────┐          │               │
│        └─────────│ EXECUTE  │◀─────────┘               │
│                  │  "Do it" │                          │
│                  └──────────┘                          │
│                                                        │
└────────────────────────────────────────────────────────┘
```

Each phase emits events that describe the agent's current cognitive state.

**[→ Detailed Agent Lifecycle](docs/AGENT_LIFECYCLE.md)**

---

## Uncertainty Escalation

A common limitation of AI agents is poor uncertainty calibration—when uncertain, they may produce confident-sounding but incorrect outputs.

AMPERE agents implement explicit uncertainty thresholds. When confidence drops below a configurable threshold, the agent escalates to a human rather than proceeding:

```kotlin
// Agent recognizes its own uncertainty
when (confidence < threshold) {
    escalate(
        reason = "OAuth2 implementation has security implications I'm not certain about",
        context = relevantMemories,
        suggestedOptions = listOf("Use PKCE", "Use implicit flow", "Consult security team")
    )
}
```

```
⚠️ EscalationRequested  [engineer-agent]  14:25:12
   │ Reason: OAuth2 implementation has security implications
   │ Confidence: 0.34
   │ Options: [Use PKCE, Use implicit flow, Consult security team]
   │ Context: 3 relevant memories attached
   └─→ Awaiting human input...
```

This implements the Computer-Human Interaction pattern: agents explain their uncertainty in terms humans can evaluate, then incorporate human judgment into subsequent reasoning.

---

## The Missing Coordination Layer

The [Agentic AI Foundation](https://aaif.io) has published standards for how AI agents connect to tools and specify permissions. But something's missing:

| AAIF Provides                         | AMPERE Provides                          |
|---------------------------------------|------------------------------------------|
| Agent ↔ Tool connections (MCP)        | Agent ↔ Agent coordination               |
| Permission specifications (Agents.md) | Transparent decision-making              |
| Reference implementation (Goose)      | Visible uncertainty escalation           |
| Python/TypeScript SDKs                | Kotlin Multiplatform (JVM, Android, iOS) |

AMPERE is designed to complement AAIF by providing a coordination and observability layer for agent-to-agent interaction.

---

## Biological Coordination Model

Traditional agent frameworks use request-response patterns where coordination happens inside opaque function calls. AMPERE draws from biological coordination patterns, which are inherently observable:

- **Signal-based communication** — Cells emit measurable chemical signals
- **Pattern-based processing** — Neural activity is externally measurable
- **Cascade responses** — Each step in an immune response leaves traceable markers

AMPERE applies these principles:

- **Event-driven coordination** — Signals are logged and reactions are traceable
- **Persistent memory** — Knowledge formation and recall are observable operations
- **Emergent behavior** — Complex outcomes arise from visible, simple rules

---

## Quick Start

> **Current Status:** Alpha release after 2+ years of development.
> Some examples show planned API. See [CLI Guide](ampere-cli/README.md) for current implementation.
>
> **[We're looking for collaborators →](https://github.com/socket-link/ampere/issues)**

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

// 3. Assign a goal and observe the event stream
team.pursue("Build a user authentication system")

team.events.collect { event ->
    when (event) {
        is Perceived -> println("👁️ ${event.agent} noticed: ${event.signal}")
        is Recalled -> println("🧠 ${event.agent} remembered: ${event.memory}")
        is Planned -> println("📋 ${event.agent} decided: ${event.plan}")
        is Executed -> println("⚡ ${event.agent} did: ${event.action}")
        is Escalated -> println("⚠️ ${event.agent} needs help: ${event.reason}")
    }
}
```

---

## CLI

The CLI provides real-time visibility into agent operations:

```bash
# Install
./gradlew :ampere-cli:installJvmDist

# Watch thought happen in real-time
./ampere-cli/ampere watch

# Search what agents have learned
./ampere-cli/ampere knowledge search "authentication patterns"

# See why a decision was made
./ampere-cli/ampere trace <decision-id>

# View agent cognitive state
./ampere-cli/ampere status --verbose
```

**[→ Complete CLI Guide](ampere-cli/README.md)**

---

## Core Concepts

AMPERE models cognition through six observable primitives:

| Concept       | What You See           | Purpose                           |
|---------------|------------------------|-----------------------------------|
| **Tickets**   | Goals being pursued    | Work units with visible lifecycle |
| **Tasks**     | Actions being taken    | Individual steps you can trace    |
| **Plans**     | Decisions being formed | The reasoning you can follow      |
| **Meetings**  | Coordination happening | Agents explaining to each other   |
| **Outcomes**  | Results being recorded | Execution history you can query   |
| **Knowledge** | Understanding forming  | Learnings you can search          |

**[→ Complete Core Concepts Guide](docs/CORE_CONCEPTS.md)**

---

## Platform Support

Kotlin Multiplatform means AMPERE runs anywhere the JVM runs:

| Platform    | Status   | Use Case                  |
|-------------|----------|---------------------------|
| **JVM**     | ✅ Stable | Server-side orchestration |
| **Android** | ✅ Stable | On-device agents          |
| **Desktop** | ✅ Stable | Local development         |
| **iOS**     | 🔄 Beta  | Cross-platform apps       |
| **CLI**     | ✅ Stable | Monitoring and management |

---

## Model Support

Model-agnostic with automatic failover:

```kotlin
val config = MultiProviderConfig(
    primary = AnthropicConfig(model = Claude.Sonnet4),
    fallback = listOf(
        GoogleConfig(model = Gemini.Pro),
        OpenAIConfig(model = OpenAI.GPT4)
    )
)
```

<<<<<<< Updated upstream
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
=======
**Supported:** Anthropic (Claude), Google (Gemini), OpenAI (GPT)
>>>>>>> Stashed changes

---

## Installation

> **Note:** Approaching initial release. Currently buildable via `./gradlew publishToMavenLocal`.

```kotlin
// Gradle (Kotlin DSL)
implementation("link.socket.ampere:ampere-core:0.1.0")
```

---

## Contributing

Looking for collaborators interested in:

- **Observability systems** — Agent monitoring and tracing
- **Kotlin Multiplatform** — Cross-platform development
- **Agent architectures** — LangChain, AutoGen, CrewAI experience
- **Cognitive science** — How humans understand complex systems

**[→ Contributing Guide](CONTRIBUTING.md)**

---

## Documentation

| Guide                                      | Description                   |
|--------------------------------------------|-------------------------------|
| [Core Concepts](docs/CORE_CONCEPTS.md)     | The six observable primitives |
| [Agent Lifecycle](docs/AGENT_LIFECYCLE.md) | The PROPEL loop in detail     |
| [CLI Reference](ampere-cli/README.md)      | Command-line tools            |
| [CLAUDE.md](CLAUDE.md)                     | AI-assisted development guide |

---

## License

```
Copyright 2026 Miley Chandonnet, Stedfast Softworks LLC

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
