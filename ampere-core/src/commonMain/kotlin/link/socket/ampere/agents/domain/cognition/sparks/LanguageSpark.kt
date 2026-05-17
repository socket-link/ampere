package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.ToolId

/**
 * A Spark that provides language-specific context and best practices.
 *
 * LanguageSpark narrows the agent's focus to a specific programming language,
 * providing idioms, conventions, and best practices. It doesn't further narrow
 * tools (inherits from role) but does narrow file access to appropriate extensions.
 *
 * This hierarchy is designed to be extensible—new languages can be added as
 * additional sealed subclasses.
 */
@Serializable
sealed class LanguageSpark : Spark {

    /**
     * Language-specific context for Kotlin development.
     *
     * Covers modern Kotlin idioms, null safety, coroutines, and multiplatform
     * considerations relevant to the Ampere project.
     */
    @Serializable
    @SerialName("LanguageSpark.Kotlin")
    data object Kotlin : LanguageSpark() {

        override val name: String = "Language:Kotlin"

        override val promptContribution: String = """
## Language: Kotlin

You are working with **Kotlin** code. Follow these idioms and best practices:

### Core Principles

- **Prefer immutability**: Use `val` over `var`, immutable collections over mutable
- **Leverage null safety**: Use nullable types explicitly, prefer safe calls (`?.`) over `!!`
- **Use scope functions appropriately**: `let` for null checks, `apply` for configuration, `run` for transformations
- **Prefer expressions over statements**: `when` expressions, `if` expressions, `try` expressions

### Kotlin-Specific Patterns

- Use data classes for DTOs and value objects
- Prefer sealed classes/interfaces for restricted hierarchies
- Use object declarations for singletons
- Leverage extension functions for clean APIs
- Use inline classes (value classes) for type safety without overhead

### Coroutines

- Use structured concurrency with appropriate scope
- Prefer `suspend` functions over callbacks
- Use `Flow` for reactive streams
- Handle cancellation properly

### Multiplatform Considerations

- Keep platform-specific code in `expect`/`actual` declarations
- Use common abstractions from kotlinx libraries
- Test on all target platforms when possible

### Code Style

- Follow Kotlin coding conventions
- Use meaningful names that express intent
- Keep functions small and focused
- Document public APIs with KDoc

### File and Package Conventions

- Source roots: `src/commonMain/kotlin/`, `src/jvmMain/kotlin/`,
  `src/main/kotlin/`. The `package` declaration mirrors the path under the
  source root.
  - `src/commonMain/kotlin/link/socket/ampere/User.kt` → `package link.socket.ampere`
  - `src/main/kotlin/com/example/Foo.kt` → `package com.example`
- Every generated `.kt` file starts with its `package` declaration followed
  by its imports.
- Prefer one top-level declaration per file when the file is named after
  the declaration; multi-declaration files are fine when the declarations
  are tightly related (e.g. a sealed class and its `data` subclasses).

### When Generating Code

- Generate **complete, compilable** code — no `TODO`s, no placeholders, no
  partial implementations.
- Include every necessary import; rely on the package convention above for
  the `package` line.
- Add KDoc on public APIs.
        """.trimIndent()

        @Transient
        override val phaseContributions: Map<CognitivePhase, String> = mapOf(
            CognitivePhase.PLAN to """
### Kotlin planning notes

- When a step will produce new files, name them with `.kt` (or `.kts` for
  Gradle scripts) and place them under the appropriate source root
  (`commonMain`, `jvmMain`, `androidMain`, etc.) so the `package` line
  follows from the path.
- Group related declarations into the same step when they belong in the
  same file (a sealed class plus its subclasses, an interface plus its
  default implementations).
            """.trimIndent(),
            CognitivePhase.EXECUTE to """
### Kotlin execution notes

- Compilation-equivalent failure modes you should surface as critical:
  missing `package` declaration, unresolved imports, signature mismatch
  against `expect`/`actual` declarations.
- Treat warnings about unsafe casts (`as`), `!!`, or platform-typed
  values as something to fix in the same step rather than defer.
            """.trimIndent(),
        )

        override val allowedTools: Set<ToolId>? = null // Inherits from role

        override val fileAccessScope: FileAccessScope = FileAccessScope(
            readPatterns = setOf("**/*.kt", "**/*.kts", "**/*.xml", "**/*.gradle*"),
            writePatterns = setOf("**/*.kt", "**/*.kts"),
            forbiddenPatterns = emptySet(),
        )
    }

    /**
     * Language-specific context for Java development.
     */
    @Serializable
    @SerialName("LanguageSpark.Java")
    data object Java : LanguageSpark() {

        override val name: String = "Language:Java"

        override val promptContribution: String = """
## Language: Java

You are working with **Java** code. Follow these idioms and best practices:

### Core Principles

- Use modern Java features (records, sealed classes, pattern matching)
- Prefer composition over inheritance
- Follow SOLID principles
- Use meaningful, descriptive names

### Java-Specific Patterns

- Use Optional for nullable return types
- Prefer streams for collection processing
- Use records for immutable data carriers
- Leverage sealed classes for restricted hierarchies
- Use interfaces for abstraction

### Code Style

- Follow standard Java conventions
- Keep classes focused and cohesive
- Document public APIs with Javadoc
- Handle exceptions appropriately
        """.trimIndent()

        override val allowedTools: Set<ToolId>? = null // Inherits from role

        override val fileAccessScope: FileAccessScope = FileAccessScope(
            readPatterns = setOf("**/*.java", "**/*.xml", "**/*.gradle*", "**/pom.xml"),
            writePatterns = setOf("**/*.java"),
            forbiddenPatterns = emptySet(),
        )
    }

    /**
     * Language-specific context for TypeScript development.
     */
    @Serializable
    @SerialName("LanguageSpark.TypeScript")
    data object TypeScript : LanguageSpark() {

        override val name: String = "Language:TypeScript"

        override val promptContribution: String = """
## Language: TypeScript

You are working with **TypeScript** code. Follow these idioms and best practices:

### Core Principles

- Leverage the type system for safety and documentation
- Prefer strict type checking settings
- Use union types and discriminated unions effectively
- Avoid `any` - use `unknown` when type is truly unknown

### TypeScript-Specific Patterns

- Use interfaces for object shapes
- Use type aliases for complex types
- Leverage generics for reusable abstractions
- Use const assertions for literal types
- Prefer functional patterns with proper typing

### Code Style

- Follow project ESLint/Prettier configuration
- Export types alongside implementations
- Document complex types with JSDoc
- Keep type definitions close to usage
        """.trimIndent()

        override val allowedTools: Set<ToolId>? = null // Inherits from role

        override val fileAccessScope: FileAccessScope = FileAccessScope(
            readPatterns = setOf("**/*.ts", "**/*.tsx", "**/*.js", "**/*.json", "**/package.json"),
            writePatterns = setOf("**/*.ts", "**/*.tsx"),
            forbiddenPatterns = setOf("**/node_modules/**"),
        )
    }

    /**
     * Language-specific context for Python development.
     */
    @Serializable
    @SerialName("LanguageSpark.Python")
    data object Python : LanguageSpark() {

        override val name: String = "Language:Python"

        override val promptContribution: String = """
## Language: Python

You are working with **Python** code. Follow these idioms and best practices:

### Core Principles

- Follow PEP 8 style guide
- Use type hints for better tooling and documentation
- Prefer explicit over implicit
- Keep it simple and readable

### Python-Specific Patterns

- Use dataclasses or Pydantic for data structures
- Leverage list/dict comprehensions appropriately
- Use context managers for resource management
- Prefer generators for large sequences
- Use descriptive variable names

### Code Style

- Follow PEP 8 naming conventions
- Document with docstrings
- Keep functions focused
- Handle exceptions explicitly
        """.trimIndent()

        override val allowedTools: Set<ToolId>? = null // Inherits from role

        override val fileAccessScope: FileAccessScope = FileAccessScope(
            readPatterns = setOf("**/*.py", "**/requirements*.txt", "**/pyproject.toml", "**/setup.py"),
            writePatterns = setOf("**/*.py"),
            forbiddenPatterns = setOf("**/__pycache__/**", "**/*.pyc", "**/venv/**", "**/.venv/**"),
        )
    }
}
