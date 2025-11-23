# Project Guidelines

## Project overview

Ampere is a Kotlin Multiplatform (KMP) project that provides a shared core for building conversational AI agents and
multiple platform apps that consume this core.

- shared: Multiplatform library containing the domain and model layers for agents, chat, conversations, capabilities,
  and tools.
    - Common code: `shared/src/commonMain/kotlin/link/socket/ampere/...`
    - Platform code: `shared/src/{androidMain,iosMain,jvmMain}`
    - Tests: `shared/src/jvmTest`
- androidApp: Android application that uses the shared module.
- iosApp: iOS application (SwiftUI) that uses the shared module.
- desktopApp: Desktop application (Compose for Desktop) that uses the shared module.
- docs/api: Generated API docs for the shared module.
- Build system: Gradle with Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`).

## How Junie should validate changes

- Docs-only changes (e.g., files in `.junie/`, `README.md`, `docs/`) do not require building or running tests.
- For changes in `shared` common/jvm code, run the JVM tests:
    - `./gradlew :shared:jvmTest`
- If compilation of the shared JVM target needs to be verified without running all tests:
    - `./gradlew :shared:compileKotlinJvm`
- Avoid building Android/iOS/Desktop apps unless the issue explicitly requires it (they are heavier and not needed for
  most library-level changes).

## Code style and conventions

- Follow standard Kotlin coding conventions and keep style consistent with surrounding code.
- Use small, minimal diffs focused on the described issue.
- Prefer adding or adjusting unit tests under `shared/src/jvmTest` when modifying core logic.

## Contribution references

- See `CONTRIBUTING.md` for general contribution workflow.
- See `CODE_OF_CONDUCT.md` for community standards.

## Notes for this repository

- The main package namespace is `link.socket.ampere.*`.
- Key domains in `shared/src/commonMain/kotlin` include:
    - `domain/chat` and `domain/conversation`: Conversation, Chat, and system instructions (tone, seriousness).
    - `domain/capability`: Capability abstractions.
    - `domain/model/llm`: LLM client/provider abstractions.
- API documentation mirrors these packages under `docs/api/shared/`.
