# The Autonomous Agent Lifecycle

This document explains how agents in Ampere autonomously work through tasks using the **PROPEL** cycle: **Perceive → Recall → Observe → Plan → Execute → Learn**.

> **Status (verified against `main`, 2026-10-10).** This document describes the
> cycle as designed. Two of the six phases do not run yet, and several steps in
> the walkthrough below are the planned API rather than today's code. What is
> and is not reachable from a consumer's hands is tracked per-phase in
> [`docs/concepts/propel-loop.md`](concepts/propel-loop.md) under *"What a
> consumer can and cannot drive today"*; the parent epic is
> [AMPR-385](https://linear.app/miley/issue/AMPR-385).

## The Core Loop

Agents in Ampere follow a continuous cycle. Two drivers implement it, and
neither runs all six phases: `AutonomousAgent.runtimeLoop` brackets
Perceive → Plan → Execute → Learn (Recall runs between them, unbracketed), and
the Arc path's `FlowPhase` tick runs Perceive → Recall → Plan → Execute. The
phases marked below are the gaps.

```
┌─────────────────────────────────────────────────────────┐
│                    PERCEIVE STATE                       │
│  Agent observes: new tickets, events, messages          │
│  Creates: Perceptions with Ideas                        │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│                  RECALL KNOWLEDGE                       │
│  Query: KnowledgeRepository for similar past work       │
│  Returns: Relevant learnings with relevance scores      │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│              OBSERVE STATE  (not implemented)           │
│  Read: Current environment, work-in-progress, drift     │
│  Compare: Against Ideas (Perceive) and Knowledge (Recall) │
│  Surface: Deltas that should re-trigger planning        │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│                       PLAN SOLUTION                     │
│  Input: Ticket, Ideas, recalled Knowledge               │
│  Output: Plan with Task list                            │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│                    EXECUTE TASKS                        │
│  For each Task in Plan:                                 │
│    - Execute with appropriate tool                      │
│    - Generate ExecutionOutcome                          │
│    - Record in OutcomeMemoryRepository                  │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│            LEARN FROM OUTCOMES                          │
│  Analyze: Outcomes, what worked, what didn't            │
│  Create: Knowledge entries                              │
│  Store: In KnowledgeRepository with tags                │
│  Publish: KnowledgeStored events; loop re-enters PERCEIVE │
│  (Arc runs close in Pulse, once per run: AMPR-402)      │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
            ┌─────┴─────┐
            │  Success? │
            └─────┬─────┘
        Yes │     │ No/Blocked
            │     │
            ▼     ▼
          Done   ┌────────────────────────────────────┐
                 │  ESCALATE - SCHEDULE MEETING       │
                 │  - TicketBlocked event             │
                 │  - Meeting scheduled               │
                 │  - Human notified                  │
                 │  - MeetingOutcomes → New work      │
                 └────────────────────────────────────┘
```

---

## Phase Sparks (Optional)

PhaseSparks add lightweight, phase-specific guidance to the system prompt during
PERCEIVE, RECALL, OBSERVE, PLAN, EXECUTE, and LEARN. They are transient: applied
at phase entry and removed at phase exit.

Enable PhaseSparks by setting `AgentConfiguration.cognitiveConfig.phaseSparks.enabled
= true`, optionally restricting to a subset of phases with `phaseSparks.phases`. The
config reaches the agent through whichever factory built it — `AgentFactory`,
`SparkAgentFactory`, and the `SparkBasedAgent.<Role>(...)` factories all take a
`cognitiveConfig`.

`enabled` is the master switch. Under it sit two independent ones, because phase
*handling* and phase *guidance* are different things to want:

| Switch | Default | What it does |
|--------|---------|--------------|
| `publishBrackets` | `true` | Publishes `CognitivePhaseEvent.PhaseEntered` / `PhaseExited` at each phase boundary. Needs a door on the agent; with none there is nothing to publish through. |
| `injectPhaseSparks` | `true` | Adds the phase's `PhaseSpark` guidance to the system prompt (and lets each role/language spark contribute its `## When <Phase>` section). |

So `enabled = true` alone behaves as it always has: brackets *and* guidance.
`enabled = true, injectPhaseSparks = false` brackets a run silently — you get the
phase boundaries in the trace while the agent's system prompt stays byte-for-byte
what it would be with phases off. That is the configuration to reach for when you
want phase observability without paying for phase prompt tokens or perturbing a
prompt you are measuring.

`AMPERE_PHASE_SPARKS=true` is a **developer switch**, not a deployment one: it
forces `enabled`, `publishBrackets`, and `injectPhaseSparks` on for every agent in
the process, whatever the config says. Use it to inspect phase behaviour in a local
run; configure `cognitiveConfig` for anything a consumer depends on.

One gap left: an Arc run has no seam for this. `ChargePhase` builds the run's
`SparkAgentFactory` itself and passes no `cognitiveConfig`
(`domain/arc/ChargePhase.kt:130-143`), so a consumer driving the loop through
`ArcSession` can only reach phase handling with the environment variable. It is
moot while nothing in the Arc path calls `withPhase` — `FlowPhase` brackets no
phases at all — but both are worth fixing together.

Trade-offs:
- Pros: clearer phase focus, more consistent reasoning at each step
- Cons: larger prompts (more tokens/latency) and more context to review in logs —
  both of which `injectPhaseSparks = false` opts out of while keeping the brackets

---

## Complete Example: Authentication Feature

> **Note:** Some of the code examples below show the planned high-level API that is currently in development. For accurate details of the current API implementation, see the [CLI Guide](../ampere-cli/README.md) and [AGENTS.md](../AGENTS.md).

Let's walk through how an authentication feature flows through the system, from ticket creation to completion.

### 1. PM Agent Creates Ticket

```kotlin
ticketOrchestrator.createTicket(
    title = "Add user authentication",
    type = TicketType.FEATURE,
    createdBy = AgentId("pm-agent")
)
// → TicketCreated event published
// → MessageThread created for discussion
// → Status: Backlog
```

**What happens:**
- Ticket is persisted in the database
- `TicketCreated` event flows through EventBus
- A MessageThread is created so agents can discuss the ticket
- All agents subscribed to ticket events are notified

---

### 2. Ticket Assigned

```kotlin
ticketOrchestrator.assignTicket(
    ticketId = ticket.id,
    agentId = AgentId("engineer-agent")
)
// → TicketAssigned event published
// → Status: Backlog → Ready
```

**What happens:**
- Ownership changes to engineer-agent
- Status automatically transitions to Ready (validated by state machine)
- `TicketAssigned` event published
- Engineer agent's event handler is triggered

---

### 3. Engineer Agent Perceives

```kotlin
// Agent's perceiveState() sees TicketAssigned event
val perception = Perception(
    ideas = listOf(
        Idea("Could use JWT with bcrypt for password hashing"),
        Idea("Need to handle token refresh")
    )
)
```

**What happens:**
- Agent observes the TicketAssigned event from the EventBus
- Reads the ticket description and requirements
- Generates initial Ideas about potential approaches
- Creates a Perception object capturing current understanding

**What the Perceive prompt carries (AMPR-403):** the description of the task in
the state's current memory cell, every `Perception.ideas` entry handed in, and
the agent's available tools. Blank ideas are dropped. The task gets there
through `Agent.rememberNewTask`, not through the role state's own `task` field —
role states (`CodeState`, `QualityState`, …) are only ever constructed as
`.blank`.

A host that perceives more of the world than the memory cells hold supplies its
own rendering through `AgentReasoning.create { perceptionContextBuilder = { … } }`.
That replaces `defaultPerceptionContext` rather than extending it. Passing the
goal as an `Idea` is the other supported channel, and composes with either.

---

### 4. Agent Recalls Knowledge

```kotlin
val relevantKnowledge = memoryService.recallRelevantKnowledge(
    MemoryContext(
        taskType = "authentication",
        tags = setOf("security", "jwt", "backend"),
        description = "Add user authentication"
    )
)
// Returns past learnings about auth implementations
```

**What happens:**
- Agent queries the KnowledgeRepository
- Semantic search finds similar past work
- Returns Knowledge entries with relevance scores
- Agent learns from previous auth implementations:
  - "JWT expiry should be 24h"
  - "Refresh tokens improve UX"
  - "Rate limiting prevents brute force"

---

### 5. Agent Observes Current State

```kotlin
val observation = agent.observe(
    ideas = perception.ideas,
    knowledge = relevantKnowledge,
    ticket = ticket
)
// Reads: Current code, recent commits, open PRs, in-flight work
// Detects: What changed since the last Arc run; drift from expected state
// Surfaces: Conflicts between recalled Knowledge and current reality
```

**What happens:**
- Agent inspects the current state of the environment
- Compares observed state against Ideas (Perceive) and Knowledge (Recall)
- Flags drift, anomalies, and contradictions for the planner
- Refines the working context that Plan will consume

**Not implemented.** There is no `Agent.observe`, no `Observation` type and no
OBSERVE service. `PlanGenerator.generate` takes a task, ideas, recalled
knowledge and the available tools, and nothing else (`PlanGenerator.kt:62-106`).
`CognitivePhase.OBSERVE` exists in the enum and in `PhaseSpark.Observe`'s prompt
(`PhaseSpark.kt:134-160`), but nothing enters the phase. This section describes
the target shape; see [`docs/concepts/propel-loop.md`](concepts/propel-loop.md)
for the invariant it is held against.

---

### 6. Agent Creates Plan

```kotlin
// What PlanGenerator actually returns (PlanGenerator.kt:258-263):
val plan = Plan.ForTask(
    task = originalTask,
    tasks = listOf(
        Task.CodeChange("Create User model"),
        Task.CodeChange("Add JWT library"),
        Task.CodeChange("Implement auth middleware"),
        Task.CodeChange("Write tests")
    ),
    estimatedComplexity = complexity,
    expectations = Expectations.blank,
)
```

**What happens:**
- Agent converts the recalled context and ideas into concrete Tasks
- Breaks work into executable steps
- Plan is remembered in agent's working memory

Three details the older version of this section got wrong:

- **The plan is a `Plan.ForTask`, not a `Plan.ForTicket`,** and `ForTicket` has
  no `observation` field — neither variant does (`Plan.kt:80-103`). The planner
  plans against the `Task` it was handed.
- **Planning does not move the ticket to `InProgress`.** `SparkBasedAgent`
  builds a throwaway `Ticket(id = "spark-task-…", status = InProgress)` per plan
  step so a tool has an `ExecutionContext` (`SparkBasedAgent.kt:473-485`); the
  real ticket's status is never touched.
- **No success criteria are set.** `expectations` is always
  `Expectations.blank` (`PlanGenerator.kt:262`). The model is asked for a
  `"steps"` array (`:205`) and each entry becomes a `Task` through the injected
  `taskFactory` (`:224-256`), which defaults to `Task.CodeChange`
  (`:350-365`).

---

### 7. Execute and Record Outcomes

```kotlin
for (task in plan.tasks) {
    val outcome = executor.execute(task)
    outcomeMemoryRepository.recordOutcome(
        ticketId = ticket.id,
        executorId = executor.id,
        approach = "JWT with bcrypt",
        outcome = outcome
    )
}
```

**What happens for each task:**

**Task 1: Create User model**
- Executor writes Kotlin data class with email/password fields
- Returns `CodeChanged.Success` with list of files modified
- Outcome recorded: "Created User.kt - SUCCESS - 45 seconds"

**Task 2: Add JWT library**
- Executor adds dependency to build.gradle.kts
- Returns `CodeChanged.Success`
- Outcome recorded: "Added JWT dependency - SUCCESS - 12 seconds"

**Task 3: Implement auth middleware**
- Executor creates middleware to verify JWT tokens
- Returns `CodeChanged.Success`
- Outcome recorded: "Created AuthMiddleware.kt - SUCCESS - 3m 12s"

**Task 4: Write tests**
- Executor writes integration tests for auth flow
- Tests run and pass
- Returns `CodeChanged.Success`
- Outcome recorded: "Created AuthTests.kt - SUCCESS - 2m 8s"

All outcomes are stored in `OutcomeMemoryRepository` for future learning.

---

### 8. Loop - Extract Knowledge

```kotlin
val knowledge = Knowledge.FromOutcome(
    outcomeId = outcome.id,
    approach = "Used JWT library with bcrypt password hashing",
    learnings = "JWT expiry should be 24h, refresh tokens improve UX, need rate limiting on auth endpoints",
    timestamp = Clock.System.now()
)
memoryService.storeKnowledge(
    knowledge = knowledge,
    tags = listOf("jwt", "authentication", "security", "bcrypt"),
    taskType = "authentication",
    complexityLevel = ComplexityLevel.MODERATE
)
// → KnowledgeStored event published
// → Available for future auth tickets
```

**What happens:**
- Agent analyzes all outcomes from the ticket
- Extracts key learnings (what worked, what didn't, best practices)
- Stores Knowledge with semantic tags
- Future tickets about "authentication" will retrieve this learning
- Knowledge is shared across all agents in the system

---

### 9a. Success Path - Ticket Completion

```kotlin
ticketOrchestrator.transitionTicketStatus(
    ticketId = ticket.id,
    newStatus = TicketStatus.Done
)
// → TicketCompleted event published
```

**What happens:**
- All tasks completed successfully
- Ticket status transitions to Done
- `TicketCompleted` event published
- MessageThread updated with completion
- PM agent is notified
- Execution outcomes and knowledge are preserved

---

### 9b. Blocked Path - Escalation

**Alternative scenario:** What if the agent gets stuck deciding between OAuth2 vs JWT?

```kotlin
ticketOrchestrator.blockTicket(
    ticketId = ticket.id,
    reason = "OAuth2 vs JWT decision needed",
    escalationType = EscalationType.DECISION_REQUIRED
)
// → TicketBlocked event
// → Meeting scheduled with engineering lead
// → Human notified via Notifier
// → MeetingOutcome.DecisionMade("Use OAuth2")
// → New action items created
```

**What happens:**
1. **Ticket Blocked:**
   - Status transitions: InProgress → Blocked
   - `TicketBlocked` event published
   - Blocking reason recorded

2. **Escalation Triggered:**
   - EscalationType.DECISION_REQUIRED requires a meeting
   - `MeetingScheduled` event published
   - Agenda item: "Discuss OAuth2 vs JWT decision"

3. **Human Notified:**
   - Notifier sends message to human (email/Slack/etc.)
   - MessageThread status: WAITING_FOR_HUMAN
   - Human can join the meeting or respond asynchronously

4. **Meeting Executed:**
   - Meeting status: Scheduled → InProgress
   - Participants discuss the decision
   - MeetingOutcomes generated:
     - `MeetingOutcome.DecisionMade("Use OAuth2 instead of JWT")`
     - `MeetingOutcome.ActionItem("Refactor auth to use OAuth2", assignedTo: engineer-agent)`

5. **New Work Created:**
   - ActionItem creates a new Task or sub-Ticket
   - Original ticket unblocked: Blocked → InProgress
   - Agent perceives the new work and continues

**Status.** The pieces exist; the chain does not run.
`TicketOrchestrator.blockTicket` does publish `TicketBlocked`, schedule the
meeting and notify the human (`agents/events/tickets/TicketOrchestrator.kt:322-449`)
— but it has no caller: no agent decides it is stuck. `MeetingScheduler` is
constructed only in tests, so meetings never run on a cadence. And step 5 does
not happen at all: a meeting's outcomes are formatted into a message
(`agents/events/meetings/MeetingOrchestrator.kt:405-432`) and never turned into
a Task or Ticket.

---

## Event Flow Visualization

Here's what flows through the EventBus during the authentication ticket:

```
Time  Event                    Publisher       Subscribers
────────────────────────────────────────────────────────────────
14:23 TicketCreated            pm-agent        [all agents, UI]
14:23 MessageThreadCreated     TicketOrch      [messaging-router]
14:24 TicketAssigned           pm-agent        [engineer-agent, UI]
14:24 TicketStatusChanged      TicketOrch      [all agents, UI]
      (Backlog → Ready)

14:25 TicketStatusChanged      TicketOrch      [all agents, UI]
      (Ready → InProgress)
14:25 TaskCreated              engineer-agent  [executor, UI]
14:28 TaskCompleted            executor        [engineer-agent, UI]
...   (more tasks)
14:45 KnowledgeStored          knowledge-svc   [all agents]
14:45 TicketStatusChanged      TicketOrch      [all agents, UI]
      (InProgress → Done)
14:45 TicketCompleted          TicketOrch      [pm-agent, UI]
```

**Two corrections to the trace above.** There is no `OutcomeRecorded` event —
`MemoryEvent` declares `KnowledgeStored`, `KnowledgeRecalled` and
`MilestoneReached` and nothing else, so an outcome write leaves a row and no
event. And the "Subscribers" column still overstates what happens: the task
lifecycle *is* published now — since AMPR-404 `AutonomousWorkLoop` opens a
`TaskLifecycle` per claimed issue and reports its terminal event in a `finally`
(`agents/execution/AutonomousWorkLoop.kt:140-166`), which is what finally lets
`MilestoneTracker` count a completion — and `EventRouter` has a registration
path (`EnvironmentService.routeEventsToAgent`). But nothing in the shipped code
calls that path, so the fan-out map is empty and no core agent is woken by
another's event.

Every action is observable and persistent. An agent *reacting* to another's
event is a registration a consumer must make; see
[`docs/concepts/event-serial-bus.md`](concepts/event-serial-bus.md).

---

## Key Takeaways

1. **Autonomy:** Agents work independently through the PROPEL loop (Perceive → Recall → Observe → Plan → Execute → Learn)
2. **Learning:** Every outcome is recorded and turned into searchable knowledge
3. **Coordination:** Events enable agents to react to each other's work
4. **Escalation:** Blockers automatically trigger meetings and human notification
5. **Persistence:** All state flows through SQLDelight for durability
6. **Observability:** Every action can be watched in real-time via the CLI

See [**Core Concepts**](CORE_CONCEPTS.md) for detailed explanations of each component.
