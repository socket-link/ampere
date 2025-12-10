# Bundled Agents

AMPERE includes **24+ pre-built agents** organized by domain. Each agent has a specialized system prompt, configurable inputs, and customizable personality settings.

> **Note:** Some of the code examples below show the planned high-level API that is currently in development. For accurate details of the current API implementation, see the [CLI Guide](ampere-cli/README.md) and [CLAUDE.md](CLAUDE.md).

---

## Agent Categories

**Location:** [`ampere-core/src/commonMain/kotlin/link/socket/ampere/domain/agent/bundled/`](../ampere-core/src/commonMain/kotlin/link/socket/ampere/domain/agent/bundled/)

### Code Agents

Agents specialized for software development tasks.

| Agent | Purpose | Best For |
|-------|---------|----------|
| **WriteCode** | Implements features and fixes bugs | Core development work |
| **APIDesign** | Designs API contracts and interfaces | New service boundaries |
| **Documentation** | Writes and maintains docs | README, API docs, guides |
| **PerformanceOptimization** | Identifies and fixes bottlenecks | Slow endpoints, memory issues |
| **SecurityReview** | Audits code for vulnerabilities | Pre-release security checks |
| **QATesting** | Writes and runs test suites | Test coverage, regression testing |

### Business Agents

Agents for product and project coordination.

| Agent | Purpose | Best For |
|-------|---------|----------|
| **ProductManager** | Breaks goals into tickets, prioritizes work | Feature planning, roadmap |
| **ProjectManager** | Tracks progress, manages dependencies | Sprint planning, blockers |
| **BusinessAnalyst** | Analyzes requirements, documents specs | Stakeholder translation |

### Reasoning Agents

Meta-agents that orchestrate other agents or handle complex reasoning.

| Agent | Purpose | Best For |
|-------|---------|----------|
| **ReAct** | Reason + Act pattern for multi-step problems | Complex debugging, research |
| **DelegateTasks** | Assigns work to appropriate specialist agents | Orchestration, routing |

### Specialized Agents

Domain-specific agents for particular knowledge areas.

| Agent | Purpose | Best For |
|-------|---------|----------|
| **Financial** | Financial analysis and calculations | Budgets, forecasts, metrics |
| **Legal** | Legal document review and compliance | Contracts, policies |
| **Travel** | Trip planning and logistics | Itineraries, bookings |
| **Health** | Health information and guidance | Wellness, medical research |
| **Cooking** | Recipe development and meal planning | Menu planning, nutrition |
| **Study** | Learning assistance and tutoring | Education, skill development |

---

## Agent Configuration

### Personality Settings

Every agent's behavior can be tuned via personality parameters:
```kotlin
agent(Engineer) {
    personality {
        directness = 0.8      // 0.0 = diplomatic, 1.0 = blunt
        creativity = 0.6      // 0.0 = conventional, 1.0 = experimental
        verbosity = 0.4       // 0.0 = terse, 1.0 = detailed
        formality = 0.5       // 0.0 = casual, 1.0 = formal
        seriousness = 0.7     // 0.0 = playful, 1.0 = serious
    }
}
```

### Custom Inputs

Agents accept domain-specific configuration:
```kotlin
agent(WriteCode) {
    inputs {
        put("language", "Kotlin")
        put("style_guide", "https://kotlinlang.org/docs/coding-conventions.html")
        put("test_framework", "kotest")
    }
}
```

### Provider Override

Override AI provider per-agent for cost optimization:
```kotlin
agent(Documentation) {
    // Use cheaper model for docs
    provider = GoogleConfig(model = Gemini.Flash)
}

agent(SecurityReview) {
    // Use strongest model for security
    provider = AnthropicConfig(model = Claude.Opus4)
}
```

---

## Creating Custom Agents

### Basic Custom Agent
```kotlin
object MyCustomAgent : AgentDefinition {
    override val id = AgentId("my-custom-agent")
    override val name = "My Custom Agent"
    override val description = "Does something specific"
    
    override val systemPrompt = """
        You are a specialized agent for [domain].
        
        Your responsibilities:
        - [Responsibility 1]
        - [Responsibility 2]
        
        When uncertain, escalate to humans rather than guessing.
    """.trimIndent()
    
    override val capabilities = setOf(
        Capability.CodeExecution,
        Capability.FileSystem,
        Capability.HumanEscalation
    )
}
```

### Registering Custom Agents
```kotlin
val team = AgentTeam.create {
    // Built-in agents
    agent(ProductManager)
    agent(Engineer)
    
    // Custom agent
    agent(MyCustomAgent) {
        personality { creativity = 0.9 }
    }
}
```

---

## Agent Capabilities

Agents declare what they can do via capabilities:

| Capability | Description |
|------------|-------------|
| `CodeExecution` | Can run code in sandboxed environment |
| `FileSystem` | Can read/write files in workspace |
| `NetworkAccess` | Can make HTTP requests |
| `HumanEscalation` | Can request human intervention |
| `AgentSpawning` | Can create sub-agents for delegation |
| `MeetingParticipation` | Can join and contribute to meetings |
| `TicketManagement` | Can create/update/close tickets |

Capabilities are checked at runtime—agents cannot perform actions outside their declared capabilities.

---

## Best Practices

### Agent Selection

1. **Start with specialists** — Use domain-specific agents rather than general-purpose ones
2. **Layer orchestration** — Use DelegateTasks agent to route complex work
3. **Match model to task** — Expensive models for critical work, cheap models for routine tasks

### Personality Tuning

1. **High directness** for code review (catch issues quickly)
2. **High creativity** for brainstorming and ideation
3. **Low verbosity** for agents that produce structured output
4. **High formality** for external-facing documentation

### Escalation Design

Every agent should have clear escalation criteria:
```kotlin
override val escalationCriteria = EscalationCriteria(
    uncertaintyThreshold = 0.3,  // Escalate if <70% confident
    blockerTypes = setOf(
        BlockerType.MISSING_REQUIREMENTS,
        BlockerType.SECURITY_CONCERN,
        BlockerType.ARCHITECTURAL_DECISION
    ),
    maxRetries = 2  // Escalate after 2 failed attempts
)
```

---

## See Also

- [Agent Lifecycle (PROPEL)](AGENT_LIFECYCLE.md) — How agents execute work
- [Core Concepts](CORE_CONCEPTS.md) — Tickets, Tasks, Plans, Meetings
- [CLI Reference](../ampere-cli/README.md) — Observing agent activity
