# Core Concepts

Understanding how these concepts work together is key to using Ampere effectively.

## 🎫 Tickets - Units of Work

**Tickets** are the fundamental work items that agents manage and execute. Similar to JIRA tickets, they represent features, bugs, tasks, or spikes.

**Key Properties:**
- **Type:** `FEATURE`, `BUG`, `TASK`, or `SPIKE`
- **Priority:** `LOW`, `MEDIUM`, `HIGH`, or `CRITICAL`
- **Status Lifecycle:** `Backlog` → `Ready` → `InProgress` → `Blocked`/`InReview` → `Done`
- **Assignment:** Which agent is responsible
- **Due Dates:** Optional deadlines

**Example:**
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

Tickets are managed by the **TicketOrchestrator**, which handles:
- Creating tickets with automatic event publishing
- Transitioning status with validation
- Assigning to agents with permission checks
- Blocking tickets and triggering escalations
- Scheduling meetings when needed

---

## ✅ Tasks - Execution Steps

**Tasks** are the individual steps that make up a plan. They represent concrete actions an agent will perform.

**Task Types:**
- `Task.CodeChange` - Modify code files
- `MeetingTask.AgendaItem` - Discussion topics in meetings

**Task Status:**
- `Pending` - Not yet started
- `InProgress` - Currently being executed
- `Blocked` - Cannot proceed (with reason)
- `Completed` - Successfully finished
- `Deferred` - Postponed for later

Tasks live inside **Plans**, which connect high-level goals (tickets) to low-level actions (tasks).

---

## 📋 Plans - From Goals to Actions

**Plans** are the bridge between what needs to be done (tickets) and how to do it (tasks). Agents create plans through reasoning about the work.

**Plan Types:**
- `Plan.ForTicket` - Plan to complete a ticket
- `Plan.ForMeeting` - Agenda for a meeting
- `Plan.ForTask` - Sub-plan for complex tasks
- `Plan.ForIdea` - Exploration plan for new ideas

**Example:**
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

## 🤝 Meetings - Coordination Points

**Meetings** enable agents (and humans) to coordinate, make decisions, and resolve blockers. They're not just metaphorical - meetings have agendas, participants, and produce concrete outcomes.

**Meeting Types:**
- `Standup` - Daily sync for team status
- `SprintPlanning` - Planning upcoming work
- `CodeReview` - PR review discussions
- `AdHoc` - Custom coordination needs

**Meeting Lifecycle:**
- `Scheduled` → `InProgress` → `Completed`
- Can be `Delayed` or `Canceled`

**When Meetings Are Triggered:**
- Tickets get blocked and need escalation
- Sprint planning time arrives
- Code reviews are requested
- Agents explicitly schedule coordination

---

## 📊 Outcomes - Execution Memory

**Outcomes** are the results of executing tasks. They form the system's **episodic memory** - a record of what was tried, what worked, and what didn't.

**ExecutionOutcome Types:**
- `CodeChanged.Success` - Code modified successfully
- `CodeChanged.Failure` - Execution failed (with error details)
- `CodeReading.Success` - Information gathered
- `NoChanges.Success` - Task completed without code changes

**MeetingOutcome Types:**
- `BlockerRaised` - Issue identified in meeting
- `GoalCreated` - New objective established
- `DecisionMade` - Choice documented
- `ActionItem` - Follow-up task assigned

**Outcome Memory:**

All outcomes are stored in the `OutcomeMemoryRepository`, enabling:
- **Learning from failures** - "What errors did we hit on similar tickets?"
- **Performance analysis** - "Which approaches complete faster?"
- **Debugging** - "Why did this ticket fail 3 times?"
- **Similarity search** - "How did we solve authentication before?"

---

## 📚 Knowledge - Semantic Learning

**Knowledge** is extracted from outcomes, plans, ideas, and perceptions. Unlike raw outcomes, knowledge represents **what was learned** and is semantically searchable.

**Knowledge Sources:**
- `Knowledge.FromOutcome` - Learnings from execution results
- `Knowledge.FromPlan` - Insights from planning process
- `Knowledge.FromTask` - Discoveries during task execution
- `Knowledge.FromIdea` - Validation of hypotheses
- `Knowledge.FromPerception` - Environmental observations

**Knowledge Properties:**
- **Approach:** What was tried
- **Learnings:** What was discovered
- **Tags:** Semantic labels (`["authentication", "jwt", "security"]`)
- **Task Type:** Category of work (`"database_migration"`)
- **Complexity:** Difficulty level (`TRIVIAL` to `NOVEL`)

**Retrieval Capabilities:**
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

The `AgentMemoryService` wraps the knowledge repository, automatically scoring relevance based on:
- Semantic similarity of descriptions
- Tag overlap
- Task type matching
- Temporal recency
- Complexity alignment

---

## How These Concepts Relate

```
TICKET (work item)
  ↓
PLAN (how to do it)
  ↓
TASKS (execution steps)
  ↓
EXECUTOR (performs tasks)
  ↓
OUTCOMES (results + memory)
  ↓
KNOWLEDGE (learnings)
  ↓
(used to inform future PLANS)

If BLOCKED → MEETING → OUTCOMES → new TICKETS/TASKS
```

See [**Agent Lifecycle**](AGENT_LIFECYCLE.md) for detailed examples of how these concepts work together in practice.
