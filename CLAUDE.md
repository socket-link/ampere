# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

### Build & Test
```bash
# Build the project
./gradlew build

# Run desktop application
./gradlew run

# Package desktop application for distribution
./gradlew package

# Run Android app
./gradlew installDebug

# Run all tests
./gradlew allTests

# Run JVM-specific tests
./gradlew jvmTest

# Run iOS tests (simulator)
./gradlew iosSimulatorArm64Test
./gradlew iosX64Test
```

### Code Quality
```bash
# Format code with ktlint
./gradlew ktlintFormat

# Check code formatting
./gradlew ktlintCheck

# Run Android lint
./gradlew lint
```

### Documentation
```bash
# Generate API documentation
./gradlew dokkaHtml

# Generate Javadocs
./gradlew generateJavadocs
```

### CLI Commands

```bash
# Build the CLI
./gradlew :ampere-cli:installJvmDist

# Start the interactive TUI dashboard
./ampere-cli/ampere              # Default: starts TUI
./ampere-cli/ampere start        # Explicit: same as above
./ampere-cli/ampere start --auto-work   # Start with background issue work

# TUI keyboard controls (while dashboard is running):
#   d - Dashboard mode (system vitals, agent status, recent events)
#   e - Event stream mode (filtered event stream)
#   m - Memory operations mode (knowledge recall/storage patterns)
#   v - Toggle verbose mode (show/hide logs in right pane)
#   h or ? - Toggle help screen
#   : - Command mode (issue commands to the system)
#   ESC - Close help / Cancel command mode
#   1-9 - Agent focus mode (detailed view of specific agent)
#   q or Ctrl+C - Exit

# Command mode (press ':' while in TUI):
#   :goal <description> - Start agent with goal
#   :help - Show available commands
#   :agents - List all active agents
#   :ticket <id> - Show ticket details
#   :thread <id> - Show conversation thread
#   :quit - Exit TUI

# Run agents with active work (with TUI visualization)
./ampere-cli/ampere run --goal "Implement FizzBuzz"    # Custom goal
./ampere-cli/ampere run --demo jazz                    # Jazz demo (Fibonacci)
./ampere-cli/ampere run --issues                       # Work on GitHub issues
./ampere-cli/ampere run --issue 42                     # Work on specific issue

# Watch events in real-time (streaming mode, no TUI)
./ampere-cli/ampere watch
./ampere-cli/ampere watch --verbose                    # Show all events including routine
./ampere-cli/ampere watch --group-cognitive-cycles     # Group knowledge operations
./ampere-cli/ampere watch --filter TaskCreated --agent agent-pm

# Static dashboard view (non-interactive)
./ampere-cli/ampere dashboard
./ampere-cli/ampere dashboard --refresh-interval 2     # Update every 2 seconds

# View conversation threads
./ampere-cli/ampere thread list
./ampere-cli/ampere thread show <thread-id>

# System status dashboard
./ampere-cli/ampere status

# View execution outcomes
./ampere-cli/ampere outcomes ticket <ticket-id>
./ampere-cli/ampere outcomes search <query>
./ampere-cli/ampere outcomes executor <executor-id>
./ampere-cli/ampere outcomes stats

# Manage GitHub issues
./ampere-cli/ampere issues create -f .ampere/issues/epic.json        # Create issues from file
./ampere-cli/ampere issues create --stdin < epic.json                # Create from stdin
./ampere-cli/ampere issues create -f epic.json --dry-run             # Validate without creating

# Interactive REPL session
./ampere-cli/ampere interactive

# Headless tests (CI/validation - no interactive UI)
./ampere-cli/ampere test jazz                          # Headless Jazz test (Fibonacci)
./ampere-cli/ampere test ticket                        # Headless issue creation test

# Legacy headless work mode (prefer 'run --issues' for TUI version)
./ampere-cli/ampere work                               # Work on issues (headless)
./ampere-cli/ampere work --continuous                  # Keep working (headless)
```

See [ampere-cli/README.md](ampere-cli/README.md) for complete CLI documentation.

## High-Level Architecture

Ampere is a Kotlin Multiplatform library for creating AI Agents and Assistants with multi-provider support. The architecture follows a layered approach:

### AI Provider Layer (`domain/ai/`)
Multi-provider AI support with three providers:
- **OpenAI**: GPT-5, GPT-5-mini, GPT-5-nano, GPT-4.1, GPT-4.1-mini, GPT-4o, GPT-4o-mini, o4-mini, o3, o3-mini
- **Anthropic (Claude)**: Opus 4.1, Opus 4, Sonnet 4, Sonnet 3.7, Haiku 3.5, Haiku 3
- **Google (Gemini)**: Multiple Gemini models

Key components:
- `AIConfiguration`: Interface with default and backup implementations
- `AIModelFeatures`: Defines available tools, reasoning level, speed, supported inputs
- `ModelLimits`: Token limits and rate limits per tier
- `RateLimits`: Tier-based rate limiting (Free, Tier1-5)

### Agent Layer (`domain/agent/` & `agents/`)
- **Agents**: Specialized AI chatbots defined by `AgentDefinition` classes with domain-specific prompts
  - `LLMAgent`: Interface for AI interaction with function calling support
  - `KoreAgent`: Concrete implementation managing conversations and tool execution
  - `MinimalAutonomousAgent`: Contract with perceive/reason/act/signal methods

- **Bundled Agents** (`domain/agent/bundled/`): 24+ pre-built agents organized in categories:
  - **Code**: APIDesignAgent, CleanJsonAgent, DocumentationAgent, PerformanceOptimizationAgent, PlatformCompatibilityAgent, QATestingAgent, ReleaseManagementAgent, SecurityReviewAgent, WriteCodeAgent
  - **General**: BusinessAgent, CareerAgent, CookingAgent, DIYAgent, EmailAgent, FinancialAgent, HealthAgent, LanguageAgent, MediaAgent, StudyAgent, TechAgent, TravelAgent
  - **Prompt**: ComparePromptsAgent, TestAgentAgent, WritePromptAgent
  - **Reasoning**: DelegateTasksAgent, ReActAgent

### Event System (`agents/events/`)
Enterprise event-driven architecture:
- **AgentEventApi**: High-level API for publishing/subscribing to events
- **Event Types**:
  - `TaskCreated`: Tasks created in the system
  - `QuestionRaised`: Questions needing attention
  - `CodeSubmitted`: Code reviews with optional requirement flags
- **EventBus**: Central event broker with pub/sub pattern
- **EventRouter**: Routes events between subscribed agents
- **EventRepository**: SQLDelight-backed persistence
- **EventLogger**: Logging interface with console implementation

### Message System (`agents/events/messages/`)
Complete messaging infrastructure:
- **MessageChannel**: Public channels (#engineering, #design, #product) and Direct messages
- **MessageThread**: Thread-based conversations with participants and status tracking
- **Message**: Individual messages with sender, timestamp, and metadata
- **MessageRouter**: Routes messages between agents and channels
- **MessageRepository**: SQLDelight persistence for messages
- **AgentMessageApi**: High-level API for message operations
- **Status tracking**: OPEN, WAITING_FOR_HUMAN, RESOLVED

### Meeting System (`agents/meetings/`)
Complete meeting management:
- **Meeting Types**: Standup, SprintPlanning, CodeReview, AdHoc
- **Meeting Statuses**: Scheduled, Delayed, InProgress, Completed, Canceled
- **Meeting Outcomes**: BlockerRaised, GoalCreated, DecisionMade, ActionItem
- **Tasks/AgendaItems**: With status tracking (Pending, InProgress, Blocked, Completed, Deferred)

### Escalation System (`agents/events/messages/escalation/`)
- **EscalationEventHandler**: Listens for escalation events and notifies humans
- **Notifier**: Interface for human notification system

### Capabilities & Tools (`domain/capability/` & `agents/tools/`)
Modular tool system:
- `AgentCapability`: Agent spawning and delegation
- `IOCapability`: File operations and CSV parsing
- `FunctionProvider`: Defines available tools for agents
- **Specific Tools**:
  - `WriteCodeFileTool`: Write code to files
  - `RunTestsTool`: Execute tests
  - `ReadCodebaseTool`: Analyze codebase
  - `AskHumanTool`: Request human input
- **Provider-specific tools**: AITool_Claude, AITool_OpenAI, AITool_Gemini

### Conversations (`domain/chat/`)
Chat session management:
- `Conversation`: Container for agent + chat history
- `ConversationHistory`: Manages chat message sequences
- `Chat`: Message types (Text, CSV, System)

### UI Layer (`ui/`)
**Compose Multiplatform** UI with screens and components:
- `HomeScreen`: List existing conversations or create new ones
- `AgentSelectionScreen`: Choose agent types
- `AgentSetupScreen`: Configure agent with inputs
- `ConversationScreen`: Active chat interface with agent
- `ModelSelectionBottomSheet`: Model selection UI with detailed info

**Model Display Components**:
- ModelDetailsSection, ModelFeaturesSection, ModelLimitsSection
- ModelRateLimitsSection, TokenUsageInfo, RateLimitChart
- PerformanceChip, SuggestedModelsSection, ModelFiltersSection

**State Management**: Uses repositories for persistent storage

### Data Layer (`data/`)
Repository pattern with SQLDelight persistence:
- **Repository<K,V>**: Generic repository with observable state
- **ConversationRepository**: Managing agent conversations
- **EventRepository**: Event persistence and querying
- **MessageRepository**: Message/thread persistence
- **UserConversationRepository**: Enhanced conversation management

### CLI Layer (`ampere-cli/`)
**Command-line tools** for observing and managing the agent substrate:
- **WatchCommand**: Real-time event streaming with filtering
- **ThreadCommand**: View and manage conversation threads
- **StatusCommand**: System-wide dashboard and metrics
- **OutcomesCommand**: Execution outcome memory and analysis

**Technologies:**
- Clikt for command-line interface
- Mordant for terminal rendering
- SQLite/SQLDelight for persistence

See [ampere-cli/README.md](ampere-cli/README.md) for complete documentation.

### Platform Targets
- **Android**: Native Android app in `ampere-android/`
- **Desktop/JVM**: Desktop app in `ampere-desktop/`
- **iOS**: iOS app in `ampere-ios/` (Xcode project)
- **CLI**: Command-line tools in `ampere-cli/` (JVM-based)

## Directory Structure

```
ampere-core/src/commonMain/kotlin/link/socket/ampere/
├── domain/
│   ├── ai/
│   │   ├── provider/          # AI providers (OpenAI, Anthropic, Google)
│   │   ├── model/             # Model definitions and features
│   │   └── configuration/     # AI configuration with backups
│   ├── agent/
│   │   └── bundled/           # 24+ pre-built agents
│   ├── assistant/             # KoreAssistant implementation
│   ├── capability/            # IOCapability, AgentCapability
│   ├── chat/                  # Conversation management
│   ├── tool/                  # Tool definitions
│   ├── koog/                  # KoogAgentFactory integration
│   ├── util/                  # Utilities
│   └── limits/                # Token/Rate limits
├── agents/
│   ├── core/                  # Core agent types and interfaces
│   ├── events/                # Event system and routing
│   │   └── messages/          # Message system
│   │       └── escalation/    # Human escalation
│   ├── meetings/              # Meeting types and management
│   └── tools/                 # Specific tool implementations
├── data/                      # Repositories and persistence
└── ui/                        # Compose Multiplatform UI

ampere-cli/
├── src/jvmMain/kotlin/link/socket/ampere/
│   ├── AmpereCommand.kt       # Root CLI command
│   ├── WatchCommand.kt        # Real-time event streaming
│   ├── ThreadCommand.kt       # Thread management (list/show)
│   ├── StatusCommand.kt       # System dashboard
│   ├── OutcomesCommand.kt     # Outcome memory (ticket/search/executor/stats)
│   ├── AmpereContext.kt       # Dependency injection
│   ├── renderer/              # CLI rendering (tables, events, colors)
│   └── util/                  # Event type parsing
└── README.md                  # Complete CLI documentation
```

## Key Patterns

### Agent System
Agents are defined by extending `AgentDefinition` with:
- `name`: Display name
- `prompt`: System prompt defining behavior
- `neededInputs`/`optionalInputs`: Configuration parameters
- Tone and seriousness settings for response style

### Function Calling
Agents can be equipped with tools via `FunctionProvider`:
- `FunctionDefinition.StringReturn`: Text-based functions
- `FunctionDefinition.CSVReturn`: Structured data functions
- Tools are automatically exposed to AI providers for function calling

### Event-Driven Architecture
- Events published via `AgentEventApi.publish()`
- Agents subscribe to specific event types
- `EventRouter` handles routing between subscribers
- Events persisted via SQLDelight for durability

### Message Routing
- Messages sent to channels or direct conversations
- `MessageRouter` handles routing based on channel/thread
- Status transitions track conversation state
- Escalation to humans when needed

### Multiplatform Structure
- `commonMain`: Shared business logic and UI
- `androidMain`: Android-specific implementations (SQL driver)
- `jvmMain`: Desktop-specific implementations (SQL driver)
- `iosMain`: iOS-specific implementations (SQL driver)

## Important Dependencies

### Core
- **OpenAI Kotlin**: `openai-kotlin` for OpenAI LLM integration
- **KOOG Agents**: `ai.koog:koog-agents` for external agent framework
- **Compose Multiplatform**: UI framework
- **Ktor**: `ktor-client-*` HTTP client for different platforms

### Persistence
- **SQLDelight**: `app.cash.sqldelight:*` for database persistence

### Utilities
- **Turtle**: Shell script execution capabilities
- **Kermit**: `co.touchlab:kermit` for logging
- **Multiplatform Markdown**: `com.mikepenz:multiplatform-markdown-renderer`

## Configuration
- AI API credentials configured via `local.properties`
- Supports multiple providers with fallback configurations
- ktlint formatting uses IntelliJ IDEA code style
- Gradle with Kotlin Multiplatform plugin
- SQLDelight for cross-platform database
