[![Maven Central](https://img.shields.io/maven-central/v/link.socket.ampere/ampere-client?color=blue&label=Download)](https://central.sonatype.com/namespace/link.socket.ampere)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# Ampere: A KMP Library for Autonomous AI Agents

<img src="readme_images/banner.png" height="450">

> **Note**
> This library, its APIs, and the sample client applications are in Alpha.
> It may change incompatibly and require manual migration in the future.
> If you have any issues, please report them on [GitHub](https://github.com/socket-link/ampere/issues).

## 📔 Overview

Ampere is a **Kotlin Multiplatform library** for building autonomous AI agent systems with **built-in coordination, memory, and learning capabilities**. Unlike traditional chatbot frameworks, Ampere provides a complete substrate for agents to collaborate on complex work through structured workflows.

### What Makes Ampere Different

Most AI frameworks focus on single-agent chat interactions. Ampere goes further by providing:

- **🎫 Work Management** - Tickets, tasks, and plans that agents create and execute
- **🤝 Agent Coordination** - Meetings, escalations, and message-based collaboration
- **🧠 Episodic Memory** - Execution outcomes that agents learn from over time
- **📚 Knowledge System** - Semantic storage and retrieval of past learnings
- **👁️ Full Observability** - Real-time CLI tools to watch agents work
- **🔄 Event-Driven Architecture** - All agent actions flow through a persistent event bus

This creates an **agent substrate** where AI agents perceive their environment, reason about work, execute plans, learn from outcomes, and coordinate with other agents - all autonomously.

---

## 🏗️ Core Concepts

Understanding how these concepts work together is key to using Ampere effectively.

### 🎫 Tickets - Units of Work

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

### ✅ Tasks - Execution Steps

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

### 📋 Plans - From Goals to Actions

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

### 🤝 Meetings - Coordination Points

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

### 📊 Outcomes - Execution Memory

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

### 📚 Knowledge - Semantic Learning

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

## 🔄 How It All Works Together

### The Autonomous Agent Lifecycle

Agents in Ampere follow a continuous **perceive → recall → reason → act → learn** cycle:

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

### Example Flow: Authentication Feature

**1. PM Agent Creates Ticket:**
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

**2. Ticket Assigned:**
```kotlin
ticketOrchestrator.assignTicket(
    ticketId = ticket.id,
    agentId = AgentId("engineer-agent")
)
// → TicketAssigned event published
// → Status: Backlog → Ready
```

**3. Engineer Agent Perceives:**
```kotlin
// Agent's perceiveState() sees TicketAssigned event
val perception = Perception(
    ideas = listOf(
        Idea("Could use JWT with bcrypt for password hashing"),
        Idea("Need to handle token refresh")
    )
)
```

**4. Agent Recalls Knowledge:**
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

**5. Agent Creates Plan:**
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

**6. Execute and Record Outcomes:**
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

**7. Extract Knowledge:**
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

**8. Complete or Escalate:**

**If successful:**
```kotlin
ticketOrchestrator.transitionTicketStatus(
    ticketId = ticket.id,
    newStatus = TicketStatus.Done
)
// → TicketCompleted event published
```

**If blocked:**
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

---

## 👁️ Observability - The Ampere CLI

All agent activity is observable in real-time through the Ampere CLI. Since everything flows through the event bus and is persisted in SQLDelight, you get complete visibility into what agents are doing.

### Installation

```bash
./gradlew :ampere-cli:installJvmDist
```

### Real-Time Event Streaming

Watch agents work in real-time:

```bash
# Watch all events
./ampere-cli/ampere watch

# Filter by event type
./ampere-cli/ampere watch --filter TicketCreated --filter TicketStatusChanged

# Filter by agent
./ampere-cli/ampere watch --agent engineer-agent

# Combine filters
./ampere-cli/ampere watch --filter TaskCreated --agent pm-agent
```

**Output:**
```
📋 TicketCreated    [pm-agent]           2025-12-01 14:23:01
   FEAT-123: Add user authentication

✅ TicketAssigned   [pm-agent]           2025-12-01 14:23:15
   FEAT-123 → engineer-agent

🔨 TicketStatusChanged [engineer-agent]  2025-12-01 14:23:45
   FEAT-123: Ready → InProgress

📝 TaskCreated      [engineer-agent]     2025-12-01 14:24:01
   Create User model with fields

✅ TaskCompleted    [engineer-agent]     2025-12-01 14:27:33
   Create User model - SUCCESS
```

### Execution Outcome Memory

Debug and learn from past executions:

```bash
# View all attempts at a ticket
./ampere-cli/ampere outcomes ticket FEAT-123

# Search for similar past work
./ampere-cli/ampere outcomes search "authentication"

# Analyze executor performance
./ampere-cli/ampere outcomes executor engineer-agent

# System-wide outcome statistics
./ampere-cli/ampere outcomes stats
```

**Example Output:**
```
Outcomes for Ticket: FEAT-123

Attempt #1 - 2025-12-01 14:27:33 - SUCCESS
  Executor: engineer-agent
  Approach: JWT with bcrypt password hashing
  Duration: 3m 48s
  Files Changed: 4

Attempt #2 - 2025-12-01 15:12:19 - FAILURE
  Executor: engineer-agent
  Approach: Added refresh token rotation
  Duration: 1m 12s
  Error: Missing database migration for refresh_tokens table

Attempt #3 - 2025-12-01 15:45:02 - SUCCESS
  Executor: engineer-agent
  Approach: Added refresh token rotation with migration
  Duration: 4m 03s
  Files Changed: 6
```

### Conversation Threads

View message-based agent coordination:

```bash
# List all active threads
./ampere-cli/ampere thread list

# Show specific thread conversation
./ampere-cli/ampere thread show thread-abc-123
```

### System Status Dashboard

Get an overview of the entire agent substrate:

```bash
./ampere-cli/ampere status
```

**Output:**
```
╔══════════════════════════════════════════════════════════╗
║              AMPERE AGENT SUBSTRATE STATUS               ║
╚══════════════════════════════════════════════════════════╝

Active Agents: 3
├─ pm-agent          (ProductManager)    Active
├─ engineer-agent    (SoftwareEngineer)  Active
└─ qa-agent          (QATester)          Active

Tickets:
├─ Backlog:     12
├─ Ready:        3
├─ InProgress:   5
├─ Blocked:      1  ⚠️
└─ Done:        47

Recent Activity (last hour):
├─ TicketCreated:        2
├─ TaskCompleted:        8
├─ MeetingScheduled:     1
└─ KnowledgeStored:      5
```

**Complete CLI Documentation:** See [ampere-cli/README.md](ampere-cli/README.md)

---

## 🥷 Bundled Agents

Ampere includes 24+ pre-built agent definitions organized by domain:

### Code Agents
- **APIDesignAgent** - REST/GraphQL API design
- **DocumentationAgent** - Code documentation
- **PerformanceOptimizationAgent** - Performance tuning
- **SecurityReviewAgent** - Security auditing
- **QATestingAgent** - Test strategy and execution
- **WriteCodeAgent** - General code implementation

### Business Agents
- **ProductManagerAgent** - Feature planning and requirements
- **ProjectManagerAgent** - Timeline and resource management
- **BusinessAnalystAgent** - Business logic and workflows

### Reasoning Agents
- **ReActAgent** - Reason + Act pattern
- **DelegateTasksAgent** - Work decomposition and delegation

### Specialized Agents
- **FinancialAgent** - Financial analysis
- **LegalAgent** - Legal document review
- **TravelAgent** - Travel planning
- **HealthAgent** - Health and wellness guidance

**Location:** [`shared/src/commonMain/kotlin/link/socket/ampere/domain/agent/bundled/`](shared/src/commonMain/kotlin/link/socket/ampere/domain/agent/bundled/)

Each agent is defined by:
- **System Prompt** - Defines behavior and expertise
- **Needed/Optional Inputs** - Configuration parameters
- **Tone** - Response style (`PROFESSIONAL`, `FRIENDLY`, `TECHNICAL`)
- **Seriousness** - Formality level (0.0 = casual, 1.0 = formal)

---

## 🤖 Multi-Provider AI Support

Ampere supports multiple AI providers with automatic failover:

### Supported Providers

**OpenAI:**
- GPT-5, GPT-5-mini, GPT-5-nano
- GPT-4.1, GPT-4.1-mini
- GPT-4o, GPT-4o-mini
- o4-mini, o3, o3-mini

**Anthropic (Claude):**
- Opus 4.1, Opus 4
- Sonnet 4, Sonnet 3.7
- Haiku 3.5, Haiku 3

**Google (Gemini):**
- Gemini Pro, Gemini Pro Vision
- Gemini Ultra

### Provider Features

Each provider configuration includes:
- **Model Capabilities** - Tools, reasoning level, speed, supported inputs
- **Token Limits** - Input/output/context window limits
- **Rate Limits** - Tier-based throttling (Free, Tier1-5)
- **Fallback Configuration** - Automatic failover to backup providers

---

## ⚡ Quick Start

### Prerequisites

- macOS (recommended) or Linux
- [Xcode](https://apps.apple.com/us/app/xcode/id497799835) (for iOS)
- [Android Studio](https://developer.android.com/studio)
- [Kotlin Multiplatform Mobile plugin](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform-mobile)

**Verify Setup:**
```bash
brew install kdoctor
kdoctor
```

### Configuration

Add AI provider API keys to `local.properties`:
```properties
anthropic_api_key=YOUR_ANTHROPIC_KEY
google_api_key=YOUR_GOOGLE_KEY
openai_api_key=YOUR_OPENAI_KEY
```

**Get API Keys:**
- [Anthropic](https://console.anthropic.com/settings/keys)
- [Google](https://aistudio.google.com/app/apikey)
- [OpenAI](https://platform.openai.com/account/api-keys)

### Running Applications

**Desktop:**
```bash
./gradlew run
./gradlew package  # Distribution in build/compose/binaries
```

**Android:**
```bash
./gradlew installDebug
```

**iOS:**
1. Find your Team ID: `kdoctor --team-ids`
2. Set `TEAM_ID` in `iosApp/Configuration/Config.xcconfig`
3. Open `iosApp/iosApp.xcodeproj` in Xcode
4. Select device and click **Run**

### CLI Tools

```bash
# Build CLI
./gradlew :ampere-cli:installJvmDist

# Watch agents work
./ampere-cli/ampere watch

# View system status
./ampere-cli/ampere status

# Check execution outcomes
./ampere-cli/ampere outcomes stats
```

---

## 🏗️ Architecture

### Directory Structure

```
shared/src/commonMain/kotlin/link/socket/ampere/
├── agents/
│   ├── core/                  # AutonomousAgent, MinimalAutonomousAgent
│   ├── events/                # EventBus, EventRouter, EventRepository
│   │   ├── tickets/           # Ticket, TicketOrchestrator
│   │   ├── meetings/          # Meeting, MeetingOrchestrator
│   │   └── messages/          # MessageRouter, MessageThread
│   │       └── escalation/    # EscalationEventHandler
│   ├── tools/                 # WriteCodeFileTool, AskHumanTool
│   └── memory/                # OutcomeMemoryRepository
│
├── domain/
│   ├── agent/bundled/         # 24+ pre-built agents
│   ├── ai/                    # Multi-provider AI support
│   │   ├── provider/          # OpenAI, Anthropic, Google clients
│   │   ├── model/             # Model definitions and features
│   │   └── configuration/     # AIConfiguration with backups
│   ├── capability/            # IOCapability, AgentCapability
│   ├── chat/                  # Conversation, ConversationHistory
│   └── tool/                  # Tool definitions
│
├── data/                      # Repository pattern
│   └── repository/            # SQLDelight-backed persistence
│
└── ui/                        # Compose Multiplatform UI
    ├── screens/               # Home, Agent Selection, Conversation
    └── components/            # Model selection, feature displays

ampere-cli/                    # Command-line observability
├── src/jvmMain/kotlin/
│   ├── WatchCommand.kt        # Real-time event streaming
│   ├── ThreadCommand.kt       # Message thread viewer
│   ├── StatusCommand.kt       # System dashboard
│   └── OutcomesCommand.kt     # Outcome memory queries
└── README.md                  # Complete CLI documentation
```

### Key Technologies

**Core:**
- **Kotlin Multiplatform** - Shared code across platforms
- **Compose Multiplatform** - UI framework
- **SQLDelight** - Cross-platform persistence
- **Ktor Client** - HTTP communication

**AI Integration:**
- **openai-kotlin** - OpenAI API client
- **KOOG Agents** - External agent framework integration

**CLI:**
- **Clikt** - Command-line interface
- **Mordant** - Terminal rendering and colors

**Utilities:**
- **Kermit** - Multiplatform logging
- **Turtle** - Shell script execution

---

## 📚 Learn More

- **[CLI Documentation](ampere-cli/README.md)** - Complete guide to CLI commands
- **[CLAUDE.md](CLAUDE.md)** - Development guide for contributors
- **[Issues](https://github.com/socket-link/ampere/issues)** - Report bugs or request features

---

## 📄 License

```
Copyright 2024 Socket Link

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
