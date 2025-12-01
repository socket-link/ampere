[![Maven Central](https://img.shields.io/maven-central/v/link.socket.ampere/ampere-client?color=blue&label=Download)](https://central.sonatype.com/namespace/link.socket.ampere)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

---


# ⚡︎Ampere: An Evolving Environment for Growing a Collaborative Multi-Agent AI System

> **Note**
> This library, its APIs, and the sample client applications are in Alpha.
> It may change incompatibly and require manual migration in the future.
> If you have any issues, please report them on [GitHub](https://github.com/socket-link/ampere/issues).

## 📔 Overview

Ampere is a **Kotlin Multiplatform library** used for building autonomous AI agent systems that have **built-in coordination, memory, and learning capabilities**. 


Unlike traditional chatbot frameworks, Ampere provides a complete platform for agents to collaborate on complex work through structured workflows.

### Environment Capabilities

- **🎫 Work Management** - Tickets, tasks, and plans that agents create and execute
- **🤝 Agent Coordination** - Meetings, escalations, and message-based collaboration
- **🧠 Episodic Memory** - Execution outcomes that agents learn from over time
- **📚 Knowledge System** - Semantic storage and retrieval of past learnings
- **👁️ Full Observability** - Real-time CLI tools to watch agents work
- **🔄 Event-Driven Architecture** - All agent actions flow through a persistent event bus

### Streamlining Multi-Agent Collaboration

Most other AI frameworks just focus on single-agent chat interactions, where the conversation model is based upon a single chat thread that contains all the available context.

Ampere enables efficient multi-agent collaboration by maintaining a simulated environment to track state and selectively retrieve only the necessary context for each agent.

This creates a **multi-agent environment** where AI agents can perceive their current state, reason about project requirements, execute their plans using Tool calls (including MCP), retain knowledge by learning from their execution outcomes, and coordinate with other agents - all with just a _single prompt_ that the user provides.

###  AniMA Model Protocol (AMP)

This library is defined in the context of a larger framework for providing efficient techniques to control multi-agent collaboration between LLMs and humans:

- **Animated Multi-Agent (AniMA) Prompting Technique**
    - An LLM prompting methodology that allows multiple AI agents to work together by simulating human interactions in a virtual environment that mirrors the real world
    - This results in a custom-trained AI system that can coordinate autonomously and efficiently to achieve complex and abstract goals
- **Perceive, Recall, Optimize, Plan, Execute, Loop (PROPEL)**
    - The lifecycle that defines how the agents interact with each other in the simulated environment
    - When combined with agent runtime lifecycles management, this allows the **AniMA** framework to be the foundation for the **AMP** protocol
- **AniMA Model Protocol (AMP)**
    - The protocol specification that defines how **AniMA** agents communicate, coordinate, plan, and learn from each other
    - Uses the **PROPEL** agent lifecycle to coordinate and execute tasks in a simulated environment
- **AMP Example Runtime Environment (AMPERE)**
    - _This library_; a Kotlin Multiplatform implementation of AMP 
    - Provides a platform for easily building up a group of AI agents that can collaborate to achieve complex goals

### How the Agents Work

Agents manage their own set of LLM chat threads, each running in an autonomous loop similar to the [ReAct pattern](https://arxiv.org/abs/2210.03629), called **PROPEL**:

1. **Perceive** - Observe tickets, events, messages, and any other available state context
2. **Recall** - Query knowledge from similar work that past agents have done
3. **Optimize** - Select tasks based on current state and desired outcome
4. **Plan** - Create an execution plan informed by past learnings, the current state, and the desired outcome
5. **Execute** - Execute the tasks from plan, and record knowledge learned from outcomes
6. **Loop** - Return to step 1

When blocked, agents automatically escalate, schedule meetings, and notify humans when necessary.

**[→ See the complete Agent Lifecycle with examples](docs/AGENT_LIFECYCLE.md)**

---

## 🏗️ Core Concepts

Ampere is built around six key domain concepts that are combined to define the environment's state:

- **[Tickets](docs/CORE_CONCEPTS.md#-tickets---units-of-work)** - Work items with lifecycle management (similar to JIRA tickets)
- **[Tasks](docs/CORE_CONCEPTS.md#-tasks---execution-steps)** - Individual execution steps that make up a plan
- **[Plans](docs/CORE_CONCEPTS.md#-plans---from-goals-to-actions)** - Bridge between goals (tickets) and actions (tasks)
- **[Meetings](docs/CORE_CONCEPTS.md#-meetings---coordination-points)** - Coordination events with agendas and concrete outcomes
- **[Outcomes](docs/CORE_CONCEPTS.md#-outcomes---execution-memory)** - Execution results that form episodic memory for learning
- **[Knowledge](docs/CORE_CONCEPTS.md#-knowledge---semantic-learning)** - Semantic learnings extracted from outcomes, searchable by future agents

**[→ Read the detailed Core Concepts guide](docs/CORE_CONCEPTS.md)**

---

## 👁️ Observability

All agent activity is observable in real-time through the **Ampere CLI**.

```bash
# Build CLI
./gradlew :ampere-cli:installJvmDist

# Watch agents work in real-time
./ampere-cli/ampere watch

# View system status
./ampere-cli/ampere status

# Query execution outcomes
./ampere-cli/ampere outcomes ticket FEAT-123
./ampere-cli/ampere outcomes search "authentication"

# View conversation threads
./ampere-cli/ampere thread list
```

**Example output:**
```
📋 TicketCreated    [pm-agent]           2025-12-01 14:23:01
   FEAT-123: Add user authentication

✅ TicketAssigned   [pm-agent]           2025-12-01 14:23:15
   FEAT-123 → engineer-agent

🔨 TicketStatusChanged [engineer-agent]  2025-12-01 14:23:45
   FEAT-123: Ready → InProgress

✅ TaskCompleted    [engineer-agent]     2025-12-01 14:27:33
   Create User model - SUCCESS
```

**[→ Complete CLI documentation](ampere-cli/README.md)**

---

## 🤖Bundled Agents

Ampere includes **24+ pre-built agents** organized by domain:

**Code:** APIDesign, Documentation, PerformanceOptimization, SecurityReview, QATesting, WriteCode

**Business:** ProductManager, ProjectManager, BusinessAnalyst

**Reasoning:** ReAct (Reason+Act pattern), DelegateTasks

**Specialized:** Financial, Legal, Travel, Health, Cooking, Study, and more

Each agent has a specialized system prompt, configurable inputs, and personality settings (tone, seriousness level).

**Location:** [`ampere-core/src/commonMain/kotlin/link/socket/ampere/domain/agent/bundled/`](ampere-core/src/commonMain/kotlin/link/socket/ampere/domain/agent/bundled/)

---

## 🤖 Multi-Provider AI Support

Ampere supports multiple AI providers with automatic failover:

- **OpenAI** - GPT-5, GPT-4.1, GPT-4o, o3/o4-mini models
- **Anthropic** - Claude Opus 4.1/4, Sonnet 4/3.7, Haiku 3.5/3
- **Google** - Gemini Pro, Gemini Ultra

Each provider configuration includes model capabilities, token limits, rate limits, and fallback options.

---

## ⚡ Quick Start

### Prerequisites

- macOS or Linux
- [Android Studio](https://developer.android.com/studio) with [Kotlin Multiplatform plugin](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform-mobile)
- [Xcode](https://apps.apple.com/us/app/xcode/id497799835) (for iOS)

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

**Get API Keys:** [Anthropic](https://console.anthropic.com/settings/keys) | [Google](https://aistudio.google.com/app/apikey) | [OpenAI](https://platform.openai.com/account/api-keys)

### Run Applications

```bash
# Desktop
./gradlew run

# Android
./gradlew installDebug

# iOS
# 1. Get Team ID: kdoctor --team-ids
# 2. Set TEAM_ID in ampere-ios/Configuration/Config.xcconfig
# 3. Open ampere-ios/ampere-ios.xcodeproj in Xcode and run
```

### Build & Test

```bash
# Build project
./gradlew build

# Run all tests
./gradlew allTests

# Format code
./gradlew ktlintFormat
```

---

## 🏗️ Architecture

### Directory Structure

```
ampere-core/src/commonMain/kotlin/link/socket/ampere/
├── agents/
│   ├── core/           # AutonomousAgent, MinimalAutonomousAgent
│   ├── events/         # EventBus, TicketOrchestrator, MeetingOrchestrator
│   ├── tools/          # WriteCodeFileTool, AskHumanTool
│   └── memory/         # OutcomeMemoryRepository, KnowledgeRepository
├── domain/
│   ├── agent/bundled/  # 24+ pre-built agents
│   ├── ai/             # Multi-provider AI support
│   └── capability/     # Agent capabilities (IO, spawning)
├── data/               # Repository pattern with SQLDelight
└── ui/                 # Compose Multiplatform UI

ampere-cli/             # Command-line observability tools
ampere-android/         # Android application
ampere-desktop/         # Desktop application
ampere-ios/             # iOS application
```

### Key Technologies

- **Kotlin Multiplatform** - Shared code across Android, iOS, Desktop, CLI
- **Compose Multiplatform** - UI framework
- **SQLDelight** - Cross-platform database persistence
- **Ktor Client** - HTTP communication
- **OpenAI Kotlin** - LLM integration
- **Clikt + Mordant** - CLI tools with rich terminal rendering

---

## 📚 Documentation

- **[Core Concepts](docs/CORE_CONCEPTS.md)** - Detailed explanation of Tickets, Tasks, Plans, Meetings, Outcomes, Knowledge
- **[Agent Lifecycle](docs/AGENT_LIFECYCLE.md)** - How agents autonomously work through tasks with examples
- **[CLI Guide](ampere-cli/README.md)** - Complete CLI commands and usage
- **[CLAUDE.md](CLAUDE.md)** - Development guide for AI contributors

---

## 📄 License

```
Copyright 2025 Stedfast Softworks LLC

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
