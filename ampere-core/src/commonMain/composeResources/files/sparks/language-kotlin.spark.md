---json
{
  "type": "language",
  "id": "kotlin",
  "name": "Language:Kotlin",
  "fileAccessScope": {
    "read": ["**/*.kt", "**/*.kts", "**/*.xml", "**/*.gradle*"],
    "write": ["**/*.kt", "**/*.kts"]
  }
}
---

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

## When Planning

### Kotlin planning notes

- When a step will produce new files, name them with `.kt` (or `.kts` for
  Gradle scripts) and place them under the appropriate source root
  (`commonMain`, `jvmMain`, `androidMain`, etc.) so the `package` line
  follows from the path.
- Group related declarations into the same step when they belong in the
  same file (a sealed class plus its subclasses, an interface plus its
  default implementations).

## When Executing

### Kotlin execution notes

- Compilation-equivalent failure modes you should surface as critical:
  missing `package` declaration, unresolved imports, signature mismatch
  against `expect`/`actual` declarations.
- Treat warnings about unsafe casts (`as`), `!!`, or platform-typed
  values as something to fix in the same step rather than defer.
