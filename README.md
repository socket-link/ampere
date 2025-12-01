[![Maven Central](https://img.shields.io/maven-central/v/link.socket.ampere/ampere-client?color=blue&label=Download)](https://central.sonatype.com/namespace/link.socket.ampere)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
> **Alpha Notice**
> - Any of these APIs may change in the future
> - Report issues on [GitHub](https://github.com/socket-link/ampere/issues)

# ⚡︎ Ampere

**A Kotlin Multiplatform library for building autonomous teams of AI agents**

Ampere lets you create AI agent systems where multiple agents collaborate like a real team - holding meetings, creating tickets, learning from experience, and coordinating on complex projects.

**Traditional AI frameworks** give you single-agent chat interactions with one long conversation thread.

**Ampere gives you** a multi-agent environment where specialized agents autonomously:
- Break down complex goals into executable tasks
- Coordinate through meetings and message channels
- Learn from past execution outcomes
- Escalate blockers and notify humans when stuck
- Maintain their own knowledge base from experience

All from a single high-level goal you provide.

--- 

## Overview

### The Problem
Building multi-agent AI systems is hard:
- Agents need to coordinate without stepping on each other
- They need access to relevant context, not everything
- They should learn from experience, not repeat mistakes
- You need visibility into what they're doing

### The Solution
Ampere gives you a **collaborative environment** with built-in:

- **🎫 Work Management** - Tickets and tasks agents create and track
- **🤝 Coordination** - Meetings, escalations, and messaging between agents
- **🧠 Memory** - Agents learn from execution outcomes over time
- **📚 Knowledge** - Semantic search of past learnings
- **👁️ Observability** - Real-time CLI to watch agents work
- **🔄 Event System** - Persistent event bus for all agent actions

Ampere is the reference implementation of the **AniMA Model Protocol (AMP)**, which is a framework for LLM prompting that enables efficient collaboration across groups of AI agents.

### The AniMA Model

- **AniMA (Animated Multi-Agent)** Prompting
    - A prompting technique that allows AI agents to simulate human team interactions in a virtual environment
    - Allows agents to choose their actions based on what they perceive, which enables efficient coordination across agents
- **AniMA Model Protocol (AMP)** Specification
    - Guidelines for how agents communicate, coordinate, and learn from each other's experiences in a group environment
    - By mimicking realistic team dynamics, groups of agents can coordinate efficiently on complex, abstract goals
- **Perceive, Recall, Optimize, Plan, Execute, Loop (PROPEL)** Runtime Loop
  - The core execution lifecycle for each agent, which is consistently being evaluated in the environment simulation 
  - Results are communicated to other agents through an event-based system of subscribing to events 
- **AMP Example Runtime Environment (AMPERE)** Developer SDK
  - This library; a Kotlin Multiplatform implementation of **AMP** using a **PROPEL** runtime loop
  - The SDK provides a simple API for configuring and interacting with **AniMA** environment runtime simulations

Instead of storing all the context for the prompt in just a single thread, Ampere lets you:
- Distribute shared knowledge across different specialized agents, based on execution outcomes
- Provide agents with only the relevant context to avoid hallucinations and misunderstandings
- Enable coordination across agents using structured workflows that are ran in the simulated environment

### Agent Lifecycle: PROPEL

Each agent runs autonomously in a loop called **PROPEL** (similar to the [ReAct pattern](https://arxiv.org/abs/2210.03629)):

1. **Perceive** - Observe tickets, events, messages, and current state
2. **Recall** - Query knowledge from similar past work
3. **Optimize** - Select the best tasks based on context
4. **Plan** - Create execution plan informed by past learnings
5. **Execute** - Execute tasks and record outcomes
6. **Loop** - Return to step 1

**[→ See detailed Agent Lifecycle with examples](docs/AGENT_LIFECYCLE.md)**

### The Environment Model

Ampere simulates a real work environment with six core concepts:

- **[Tickets](docs/CORE_CONCEPTS.md#-tickets---units-of-work)** - Work items with lifecycle tracking (like JIRA)
- **[Tasks](docs/CORE_CONCEPTS.md#-tasks---execution-steps)** - Individual steps that make up a plan
- **[Plans](docs/CORE_CONCEPTS.md#-plans---from-goals-to-actions)** - Bridge between goals (tickets) and actions (tasks)
- **[Meetings](docs/CORE_CONCEPTS.md#-meetings---coordination-points)** - Coordination events with agendas and outcomes
- **[Outcomes](docs/CORE_CONCEPTS.md#-outcomes---execution-memory)** - Execution results forming episodic memory
- **[Knowledge](docs/CORE_CONCEPTS.md#-knowledge---semantic-learning)** - Semantic learnings searchable by future agents

**[→ Read the complete Core Concepts guide](docs/CORE_CONCEPTS.md)**

## Configuration Options

Ampere includes **24+ pre-built agents** organized by domain:

Each agent has its own specialized system prompt, with configurable inputs from the user, and customizable personality settings (tone, seriousness, directness, and more).

**Location:** [`ampere-core/src/commonMain/kotlin/link/socket/ampere/domain/agent/bundled/`](ampere-core/src/commonMain/kotlin/link/socket/ampere/domain/agent/bundled/)

- **Code:** APIDesign, Documentation, PerformanceOptimization, SecurityReview, QATesting, WriteCode

- **Business:** ProductManager, ProjectManager, BusinessAnalyst

- **Reasoning:** ReAct (Reason+Act pattern), DelegateTasks

- **Specialized:** Financial, Legal, Travel, Health, Cooking, Study, and more

## Multi-Provider AI Support

Ampere supports configuring many models from different AI providers automatic failover:

- **Anthropic**
    - Claude Opus 4.1, 4
    - Claude Sonnet 4, 3.7
    - Claude Haiku 3.5, 3
- **Google**
    - Gemini 2.5 Pro, 2 Pro
    - Gemini 2.5, 2
- **OpenAI** 
    - GPT 5, 4.1, 4o, o3, o4-mini 

Each provider configuration includes the model capabilities, token limits, rate limits, and fallback options.

## Observability

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

## Architecture

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

### More Resources

- **[Core Concepts](docs/CORE_CONCEPTS.md)** - Detailed explanation of Tickets, Tasks, Plans, Meetings, Outcomes, Knowledge
- **[Agent Lifecycle](docs/AGENT_LIFECYCLE.md)** - How agents autonomously work through tasks with examples
- **[CLI Guide](ampere-cli/README.md)** - Complete CLI commands and usage
- **[CLAUDE.md](CLAUDE.md)** - Development guide for AI contributors

## Development Guide

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

- **Generate API Key:** [Anthropic](https://console.anthropic.com/settings/keys) | [Google](https://aistudio.google.com/app/apikey) | [OpenAI](https://platform.openai.com/account/api-keys)
- Provide the values for those keys in the `ampere/local.properties` file:
  - This file is ignored by Git, so you can safely store your keys there
```properties
anthropic_api_key=YOUR_ANTHROPIC_KEY
google_api_key=YOUR_GOOGLE_KEY
openai_api_key=YOUR_OPENAI_KEY
```

### Build & Test Project

```bash
# Build project
./gradlew build

# Run all tests
./gradlew allTests

# Format code
./gradlew ktlintFormat

# Check code formatting
./gradlew ktlintCheck
```

### Run Platform-Specific Targets

```bash
# Desktop application
./gradlew run
./gradlew package

# Android
./gradlew installDebug
./gradlew lint

# Run JVM-specific tests
./gradlew jvmTest

# Run iOS tests
# 1. Get Team ID: kdoctor --team-ids
# 2. Set TEAM_ID in ampere-ios/Configuration/Config.xcconfig
# 3. Open ampere-ios/ampere-ios.xcodeproj in Xcode and run
./gradlew iosSimulatorArm64Test
./gradlew iosX64Test
```

### Generate API Documentation

```bash
# Generate API documentation
./gradlew dokkaHtml

# Generate Javadocs
./gradlew generateJavadocs
```

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
