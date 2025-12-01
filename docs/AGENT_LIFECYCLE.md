# The Autonomous Agent Lifecycle

This document explains how agents in Ampere autonomously work through tasks using the **perceive → recall → reason → act → learn** cycle.

## The Core Loop

Agents in Ampere follow a continuous cycle:

```
┌─────────────────────────────────────────────────────────┐
│                    PERCEIVE STATE                        │
│  Agent observes: new tickets, events, messages          │
│  Creates: Perceptions with Ideas                        │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│                  RECALL KNOWLEDGE                        │
│  Query: KnowledgeRepository for similar past work       │
│  Returns: Relevant learnings with relevance scores      │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│              REASON - CREATE PLAN                        │
│  Input: Ticket, Ideas, Past Knowledge                   │
│  Output: Plan with Task list                            │
│  Status: Ticket moves InProgress                        │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│                ACT - EXECUTE PLAN                        │
│  For each Task in Plan:                                 │
│    - Execute with appropriate tool                      │
│    - Generate ExecutionOutcome                          │
│    - Record in OutcomeMemoryRepository                  │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│            LEARN - EXTRACT KNOWLEDGE                     │
│  Analyze: Outcomes, what worked, what didn't            │
│  Create: Knowledge entries                              │
│  Store: In KnowledgeRepository with tags                │
│  Publish: KnowledgeStored events                        │
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

## Complete Example: Authentication Feature

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

### 5. Agent Creates Plan

```kotlin
val plan = Plan.ForTicket(
    ticket = ticket,
    tasks = listOf(
        Task.CodeChange("Create User model"),
        Task.CodeChange("Add JWT library"),
        Task.CodeChange("Implement auth middleware"),
        Task.CodeChange("Write tests")
    )
)
// → Status: Ready → InProgress
```

**What happens:**
- Agent reasons about the ticket using Ideas + Knowledge
- Breaks work into concrete Tasks
- Sets expectations and success criteria
- Ticket status transitions to InProgress
- Plan is remembered in agent's working memory

---

### 6. Execute and Record Outcomes

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

### 7. Extract Knowledge

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

### 8a. Success Path - Ticket Completion

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

### 8b. Blocked Path - Escalation

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
14:28 OutcomeRecorded          executor        [knowledge-service]
...   (more tasks)
14:45 KnowledgeStored          knowledge-svc   [all agents]
14:45 TicketStatusChanged      TicketOrch      [all agents, UI]
      (InProgress → Done)
14:45 TicketCompleted          TicketOrch      [pm-agent, UI]
```

Every action is observable, persistent, and can trigger reactive behavior in other agents.

---

## Key Takeaways

1. **Autonomy:** Agents work independently through the perceive-reason-act-learn loop
2. **Learning:** Every outcome is recorded and turned into searchable knowledge
3. **Coordination:** Events enable agents to react to each other's work
4. **Escalation:** Blockers automatically trigger meetings and human notification
5. **Persistence:** All state flows through SQLDelight for durability
6. **Observability:** Every action can be watched in real-time via the CLI

See [**Core Concepts**](CORE_CONCEPTS.md) for detailed explanations of each component.
