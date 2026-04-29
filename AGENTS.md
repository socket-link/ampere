# AGENTS.md

Ampere is a transparent cognitive engine built in Kotlin Multiplatform, where AI reasoning is visible by design. Read [SOUL.md](SOUL.md) for project philosophy and values.

## Concepts

This repo's load-bearing primitives are documented as concept cells in
[`docs/concepts/`](docs/concepts/). The index is at
[`docs/concepts/_index.md`](docs/concepts/_index.md).

**Before starting any task:**
- Read `docs/concepts/_index.md`.
- For any concept whose `tracked_sources` include files you will touch, read
  that concept file in full before writing code. Treat the `Invariants` and
  `Anti-patterns` sections as binding.

**When your changes touch `tracked_sources`:**
- Re-read the concept file before opening the PR.
- If your changes *confirm* the concept as written, update `last_verified` to
  today's date. No other change required.
- If your changes *contradict* the concept (new invariant, retired one,
  changed file path, new common operation), update the relevant sections.
- If you discover a wrong assumption you almost made, add it to
  `Anti-patterns` with one sentence of reasoning.
- The concept file update is part of your diff. Do not split it into a
  follow-up PR.

**Commit trailers:**
- When a commit touches `tracked_sources` for a concept and you have verified
  the concept is still accurate without changing the file, include the trailer
  `Concept-Verified: <ConceptName>` in the commit message. This satisfies
  the validator without requiring a separate `last_verified` bump.

## Development Commands

### Build & Test
```bash
./gradlew build                        # Build the project
./gradlew allTests                     # Run all tests
./gradlew jvmTest                      # Run JVM-specific tests (fastest, primary gate)
./gradlew iosSimulatorArm64Test        # Run iOS tests (Apple Silicon simulator)
./gradlew run                          # Run desktop application
./gradlew installDebug                 # Run Android app
./gradlew package                      # Package desktop app for distribution
```

### Code Quality
```bash
./gradlew ktlintFormat                 # Auto-format code
./gradlew ktlintCheck                  # Check formatting
./gradlew lint                         # Android lint
./gradlew dokkaHtml                    # Generate API documentation
```

### CLI
```bash
./gradlew :ampere-cli:installDist      # Build the CLI

# TUI dashboard
./ampere-cli/ampere                                    # Idle dashboard
./ampere-cli/ampere --auto-work                        # Start with background issue work
./ampere-cli/ampere --goal "Implement FizzBuzz"        # Custom goal
./ampere-cli/ampere --issues                           # Work on GitHub issues
./ampere-cli/ampere --issue 42                         # Work on specific issue

# TUI controls: d=dashboard, e=events, m=memory, v=verbose, ?=help, :=command mode, q=quit

# Other CLI commands
./ampere-cli/ampere thread list                        # View conversation threads
./ampere-cli/ampere status                             # System status dashboard
./ampere-cli/ampere outcomes stats                     # Execution outcome stats
./ampere-cli/ampere issues create -f epic.json         # Create issues from file
./ampere-cli/ampere test agent                         # Headless agent test
```

See [ampere-cli/README.md](ampere-cli/README.md) for full CLI documentation.

## Architecture at a Glance

Ampere follows a layered architecture built on the **PROPEL** cognitive loop (Perceive, Recall, Optimize, Plan, Execute, Loop) and six core primitives (Tickets, Tasks, Plans, Meetings, Outcomes, Knowledge).

| Layer | Location | Purpose |
|-------|----------|---------|
| AI Providers | `domain/ai/` | Multi-provider support (Anthropic, OpenAI, Google) with fallback chains |
| Agent Definitions | `domain/agent/` | Agent identities, prompts, and bundled agent catalog |
| Cognitive Core | `agents/core/` | PROPEL loop, autonomous agent contracts |
| Event System | `agents/events/` | EventBus, routing, persistence, messaging, escalation |
| Coordination | `agents/meetings/` | Standup, sprint planning, code review, ad-hoc meetings |
| Tools | `agents/tools/` | WriteCode, RunTests, ReadCodebase, AskHuman |
| Persistence | `data/` | SQLDelight repositories with observable state |
| UI | `ui/` | Compose Multiplatform screens and components |
| CLI | `ampere-cli/` | TUI dashboard, event streaming, thread management |

For details: [Core Concepts](docs/CORE_CONCEPTS.md) | [Agent Lifecycle](docs/AGENT_LIFECYCLE.md) | [Architecture](docs/ARCS.md)

## Key Paths

| Module | Purpose |
|--------|---------|
| `ampere-core/src/commonMain/` | Shared business logic (agents, events, domain, UI) |
| `ampere-core/src/jvmMain/` | JVM platform implementations (SQL drivers) |
| `ampere-core/src/androidMain/` | Android platform implementations |
| `ampere-core/src/iosMain/` | iOS platform implementations |
| `ampere-cli/` | Command-line TUI and tools |
| `ampere-android/` | Android application |
| `ampere-desktop/` | Desktop application (Compose) |
| `ampere-ios/` | iOS application (Xcode project) |
| `docs/` | Architecture and concept documentation |

## Before You Change Anything

- Read the file you're modifying. Read its tests. Read its callers.
- Check [docs/CORE_CONCEPTS.md](docs/CORE_CONCEPTS.md) if touching primitives (Tickets, Tasks, Plans, Meetings, Outcomes, Knowledge).
- Check [docs/AGENT_LIFECYCLE.md](docs/AGENT_LIFECYCLE.md) if touching the PROPEL loop.
- Check [docs/ARCS.md](docs/ARCS.md) if touching orchestration patterns.

## Kotlin Conventions

- Sealed classes/interfaces for closed type hierarchies
- Data classes for immutable value types
- `expect`/`actual` for platform-specific code
- Follow existing SQLDelight patterns for persistence
- ktlint with IntelliJ IDEA code style (auto-format with `./gradlew ktlintFormat`)

## Agent System Rules

- Every new event type needs: serializer, EventBus registration, CLI display handler
- Agent prompts belong in `AgentDefinition` subclasses, not loose strings
- Tool functions must handle errors gracefully and return structured results
- Confidence thresholds should be configurable, not hardcoded
- New agents go in `domain/agent/bundled/` following existing category organization

## Testing

- **Primary gate:** `./gradlew jvmTest` — run this after every change
- Test behavior, not implementation details
- Mock AI provider responses in tests; never call real APIs
- New features need tests in `commonTest`

## Safety Boundaries

- **Never** modify `local.properties` (contains API keys)
- **Never** push directly to `main`
- **Never** delete SQLDelight migration files
- **Never** commit API keys, tokens, or secrets
- **Always** run `./gradlew jvmTest` before considering work complete
- **Always** run `./gradlew ktlintFormat` before committing

## Permissions

- **File read:** any file in the repository
- **File write:** `src/`, `test/`, `docs/`, config files (not `local.properties`)
- **Git:** branch creation, commits (with review)
- **Build/test:** full access to `./gradlew` commands
- **External:** no network requests or API calls without explicit approval
