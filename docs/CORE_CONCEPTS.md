# Core Concepts

This document explains the foundational concepts of AMPERE and how they work together to enable autonomous agent coordination.

> **Note:** Some of the code examples below show the planned high-level API that is currently in development. For accurate details of the current API implementation, see the [CLI Guide](ampere-cli/README.md) and [AGENTS.md](../AGENTS.md).

---

## The AniMA Model

AMPERE uses the **Animated Multi-Agent (AniMA)** prompting technique in defining the AniMA Model Protocol (AMP), which describes how agents communicate, coordinate, and learn from each other.

### Why "Animated"?

Traditional agent frameworks treat agents as stateless functions: input → process → output. AniMA treats agents as **animated entities** with:

- **Persistent identity** — Agents maintain context across interactions
- **Environmental awareness** — Agents perceive and react to system state
- **Social dynamics** — Agents coordinate through human-like team patterns
- **Experiential learning** — Agents improve from past outcomes

### The Protocol Stack
```
┌─────────────────────────────────────────────────────────┐
│                    AMPERE (This Library)                │
│         Kotlin Multiplatform implementation             │
├─────────────────────────────────────────────────────────┤
│                     AMP Protocol                        │
│     Guidelines for agent communication & learning       │
├─────────────────────────────────────────────────────────┤
│                   AniMA Prompting                       │
│      Technique for simulating team interactions         │
├─────────────────────────────────────────────────────────┤
│                    AAIF Standards                       │
│          MCP (tools) · Agents.md (permissions)          │
└─────────────────────────────────────────────────────────┘
```

**AniMA (Animated Multi-Agent) Prompting** — A prompting technique that allows AI agents to simulate human team interactions. Agents choose actions based on environmental perception rather than rigid scripts.

**AMP (AniMA Model Protocol)** — Specification for how agents communicate, coordinate, and learn from each other. Defines message formats, coordination patterns, and memory structures.

**AMPERE (AMP Example Runtime Environment)** — This library; a production implementation of AMP using the PROPEL runtime loop, targeting Kotlin Multiplatform.

### Relationship to AAIF

The [Agentic AI Foundation](https://www.linuxfoundation.org/press/linux-foundation-launches-agentic-ai-foundation) standardizes:
- **MCP** — How agents connect to external tools
- **Agents.md** — How agents specify permissions

AMPERE builds *on top of* these standards, adding:
- **Agent-to-agent coordination** — How agents work together
- **Persistent memory** — How agents learn from experience
- **Emergent orchestration** — How complex behavior arises from simple rules

---

## The Six Primitives

AMPERE simulates a work environment using six core concepts that mirror how human teams operate.

### 🎫 Tickets — Units of Work

**Tickets** are the fundamental work items that agents manage and execute. They represent features, bugs, tasks, or research spikes.

| Property | Description |
|----------|-------------|
| **Type** | `FEATURE`, `BUG`, `TASK`, or `SPIKE` |
| **Priority** | `LOW`, `MEDIUM`, `HIGH`, or `CRITICAL` |
| **Status** | `Backlog` → `Ready` → `InProgress` → `Blocked`/`InReview` → `Done` |
| **Assignment** | Which agent owns the work |
| **Due Date** | Optional deadline |
```kotlin
val ticket = Ticket(
    id = TicketId("FEAT-123"),
    title = "Add user authentication",
    description = "Implement JWT-based authentication with refresh tokens",
    type = TicketType.FEATURE,
    priority = TicketPriority.HIGH,
    status = TicketStatus.Ready,
    assignedAgentId = AgentId("engineer-agent")
)
```

The **TicketOrchestrator** manages ticket lifecycle:
- Creates tickets with automatic event publishing
- Validates status transitions
- Handles assignment with permission checks
- Triggers escalations when tickets block
- Schedules meetings when coordination is needed

---

### ✅ Tasks — Execution Steps

**Tasks** are individual actions an agent performs. They're the atomic units of execution.

**Task Types:**
- `Task.CodeChange` — Modify source files
- `MeetingTask.AgendaItem` — Discussion topics in meetings

**Task Status:**
- `Pending` — Not yet started
- `InProgress` — Currently executing
- `Blocked` — Cannot proceed (with reason)
- `Completed` — Successfully finished
- `Deferred` — Postponed for later

Tasks live inside Plans, connecting high-level goals to concrete actions.

---

### 📋 Plans — From Goals to Actions

**Plans** bridge what needs to be done (tickets) with how to do it (tasks). Agents create plans through reasoning about work requirements and past experience.

**Plan Types:**
- `Plan.ForTicket` — Steps to complete a ticket
- `Plan.ForMeeting` — Agenda for a coordination meeting
- `Plan.ForTask` — Sub-plan for complex individual tasks
- `Plan.ForIdea` — Exploration plan for validating hypotheses
```kotlin
val plan = Plan.ForTicket(
    ticket = ticket,
    tasks = listOf(
        Task.CodeChange("Create User model with email/password fields"),
        Task.CodeChange("Add JWT library dependency"),
        Task.CodeChange("Implement authentication middleware"),
        Task.CodeChange("Write integration tests")
    ),
    estimatedComplexity = 7,
    expectations = Expectations(
        successCriteria = "Users can login and receive JWT tokens",
        testStrategy = "Integration tests with test database"
    )
)
```

---

### 🤝 Meetings — Coordination Points

**Meetings** enable agents (and humans) to coordinate, make decisions, and resolve blockers. They're not metaphorical—meetings have agendas, participants, and produce concrete outcomes.

**Meeting Types:**
- `Standup` — Daily sync for team status
- `SprintPlanning` — Planning upcoming work
- `CodeReview` — PR review discussions
- `AdHoc` — Custom coordination needs

**Meeting Lifecycle:**
`Scheduled` → `InProgress` → `Completed` (or `Delayed`/`Canceled`)

**Triggers for Meetings:**
- Tickets become blocked and need escalation
- Sprint planning cadence arrives
- Code reviews are requested
- Agents explicitly request coordination

---

### 📊 Outcomes — Execution Memory

**Outcomes** record the results of executing tasks. They form the system's **episodic memory**—what was tried, what worked, what failed.

**ExecutionOutcome Types:**
- `CodeChanged.Success` — Code modified successfully
- `CodeChanged.Failure` — Execution failed (with error details)
- `CodeReading.Success` — Information gathered
- `NoChanges.Success` — Task completed without code changes

**MeetingOutcome Types:**
- `BlockerRaised` — Issue identified during discussion
- `GoalCreated` — New objective established
- `DecisionMade` — Choice documented
- `ActionItem` — Follow-up task assigned

All outcomes are stored in `OutcomeMemoryRepository`, enabling:
- **Learning from failures** — "What errors occurred on similar tickets?"
- **Performance analysis** — "Which approaches complete faster?"
- **Debugging** — "Why did this ticket fail 3 times?"
- **Similarity search** — "How did we solve authentication before?"

---

### 📚 Knowledge — Semantic Learning

**Knowledge** is extracted from outcomes, plans, ideas, and perceptions. Unlike raw outcomes, knowledge represents **consolidated learnings** that are semantically searchable.

**Knowledge Sources:**
- `Knowledge.FromOutcome` — Learnings from execution results
- `Knowledge.FromPlan` — Insights from planning process
- `Knowledge.FromTask` — Discoveries during task execution
- `Knowledge.FromIdea` — Validation of hypotheses
- `Knowledge.FromPerception` — Environmental observations

**Knowledge Properties:**

| Property | Description |
|----------|-------------|
| **Approach** | What strategy was attempted |
| **Learnings** | What was discovered |
| **Tags** | Semantic labels (`["authentication", "jwt", "security"]`) |
| **Task Type** | Category of work (`"database_migration"`) |
| **Complexity** | Difficulty level (`TRIVIAL` to `NOVEL`) |
```kotlin
// Find similar past experiences
knowledgeRepository.findSimilarKnowledge("implement OAuth2 authentication")

// Filter by task type
knowledgeRepository.findKnowledgeByTaskType("database_migration")

// Multi-dimensional search
knowledgeRepository.searchKnowledgeByContext(
    type = KnowledgeType.FROM_OUTCOME,
    taskType = "api_integration",
    tags = listOf("rest", "authentication"),
    complexity = ComplexityLevel.MODERATE
)
```

The `AgentMemoryService` wraps the repository, scoring relevance based on:
- Semantic similarity of descriptions
- Tag overlap
- Task type matching
- Temporal recency
- Complexity alignment

---

## How Concepts Relate
```
TICKET (what needs doing)
    ↓
PLAN (how to do it)
    ↓
TASKS (concrete steps)
    ↓
EXECUTION (agent performs work)
    ↓
OUTCOMES (what happened)
    ↓
KNOWLEDGE (what was learned)
    ↓
(informs future PLANS)

If BLOCKED:
  → MEETING scheduled
  → OUTCOMES from discussion
  → New TICKETS or TASKS created
  → Work continues
```

---

## Biological Analogies

AMPERE's design draws from biological systems:

| Concept | Biological Analog | Why It Matters |
|---------|-------------------|----------------|
| **Tickets** | Stimuli/Goals | External triggers that initiate behavior |
| **Tasks** | Motor actions | Discrete movements toward objectives |
| **Plans** | Neural pathways | Learned routes from stimulus to response |
| **Meetings** | Synaptic convergence | Points where multiple signals integrate |
| **Outcomes** | Episodic memory | Specific experiences with context |
| **Knowledge** | Semantic memory | Generalized learnings without specific context |
| **Events** | Neurotransmitters | Signals that propagate through the system |
| **EventBus** | Nervous system | Infrastructure for signal transmission |

This isn't just metaphor—these patterns enable **emergent coordination** that rigid orchestration cannot achieve.

---

## See Also

- [Agent Lifecycle (PROPEL)](AGENT_LIFECYCLE.md) — The execution loop in detail
- [Bundled Agents](AGENTS.md) — Available agent types
- [CLI Reference](../ampere-cli/README.md) — Observing these concepts in action
