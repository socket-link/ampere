# Development Guide

This guide covers setting up a development environment for contributing to AMPERE.

---

## Prerequisites

### Required

- **macOS or Linux** (Windows via WSL2)
- **Java 21+** — Required for Kotlin Multiplatform
- IDE Support:
  - **Android Studio** with [Kotlin Multiplatform plugin](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform-mobile)
  - **IntelliJ IDEA**

### Optional (for full platform support)

- **Xcode 15+** — Required for iOS development
- **Docker** — For containerized testing

### Verify Setup
```bash
# Install kdoctor for environment verification
brew install kdoctor

# Check your setup
kdoctor
```

kdoctor will identify any missing dependencies or configuration issues.

---

## API Keys

AMPERE supports multiple AI providers. You'll need at least one API key:

| Provider  | Get Key                                                              | Required    |
|-----------|----------------------------------------------------------------------|-------------|
| Anthropic | [console.anthropic.com](https://console.anthropic.com/settings/keys) | Recommended |
| Google    | [aistudio.google.com](https://aistudio.google.com/app/apikey)        | Optional    |
| OpenAI    | [platform.openai.com](https://platform.openai.com/account/api-keys)  | Optional    |

### Configuration

Create `local.properties` in the project root (this file is gitignored):
```properties
# At least one key required
anthropic_api_key=sk-ant-...
google_api_key=AI...
openai_api_key=sk-...
```

---

## Building

### Full Build
```bash
# Build all modules
./gradlew build

# Build without tests (faster)
./gradlew assemble
```

### Platform-Specific Builds
```bash
# Desktop (JVM)
./gradlew :ampere-desktop:run
./gradlew :ampere-desktop:package

# Android
./gradlew :ampere-android:installDebug
./gradlew :ampere-android:lint

# CLI
./gradlew :ampere-cli:installJvmDist
./ampere-cli/ampere --help

# iOS (requires Xcode)
# 1. Get Team ID: kdoctor --team-ids
# 2. Set TEAM_ID in ampere-ios/Configuration/Config.xcconfig
# 3. Open ampere-ios/ampere-ios.xcodeproj in Xcode
```

---

## Testing

### Run All Tests
```bash
./gradlew allTests
```

### Platform-Specific Tests
```bash
# JVM tests (fastest)
./gradlew jvmTest

# Android tests
./gradlew :ampere-android:testDebugUnitTest

# iOS Simulator tests
./gradlew iosSimulatorArm64Test  # Apple Silicon
./gradlew iosX64Test              # Intel
```

### Test Coverage
```bash
./gradlew koverReport
# Report: build/reports/kover/html/index.html
```

---

## Code Quality

### Formatting

AMPERE uses ktlint for consistent formatting:
```bash
# Check formatting
./gradlew ktlintCheck

# Auto-fix formatting issues
./gradlew ktlintFormat
```

### Linting
```bash
# Kotlin linting
./gradlew detekt

# Android-specific linting
./gradlew :ampere-android:lint
```

### Pre-commit Hook (Recommended)
```bash
# Install git hooks
./gradlew installGitHooks
```

This runs `ktlintCheck` before each commit.

---

## Documentation

### Generate API Docs
```bash
# KDoc documentation
./gradlew dokkaHtml
# Output: build/dokka/html/index.html

# Javadoc format
./gradlew generateJavadocs
```

### Local Documentation Server
```bash
# Serve docs locally
cd build/dokka/html
python -m http.server 8000
# Open http://localhost:8000
```

---

## Project Structure
```
ampere/
├── ampere-core/          # Shared multiplatform code
│   └── src/
│       ├── commonMain/   # Platform-agnostic code
│       ├── jvmMain/      # JVM-specific implementations
│       ├── androidMain/  # Android-specific code
│       └── iosMain/      # iOS-specific code
│
├── ampere-cli/           # Command-line interface
├── ampere-android/       # Android application
├── ampere-desktop/       # Desktop application (Compose)
├── ampere-ios/           # iOS application (SwiftUI + KMP)
│
├── docs/                 # Documentation
├── gradle/               # Gradle wrapper and version catalog
└── local.properties      # API keys (gitignored)
```

### Key Packages
```
link.socket.ampere/
├── agents/
│   ├── core/             # AutonomousAgent base classes
│   ├── events/           # EventBus, orchestrators
│   ├── tools/            # Agent tools (code, human escalation)
│   └── memory/           # Outcome and knowledge repositories
│
├── domain/
│   ├── agent/bundled/    # Pre-built agents
│   ├── ai/               # Multi-provider AI support
│   └── capability/       # Agent capability definitions
│
├── data/                 # SQLDelight repositories
└── ui/                   # Compose Multiplatform UI
```

---

## Common Tasks

### Adding a New Agent

1. Create agent definition in `domain/agent/bundled/`
2. Define system prompt and capabilities
3. Register in agent catalog
4. Add tests in `commonTest`

### Adding a New Event Type

1. Define event class extending `AmpereEvent`
2. Add serializer to `EventSerializerBus`
3. Update CLI watch command to display new event
4. Document in event types reference

### Adding a New AI Provider

1. Implement `AIProvider` interface
2. Add configuration data class
3. Register in `MultiProviderConfig`
4. Add to fallback chain if desired

---

## Troubleshooting

### Build Failures

**"Could not resolve dependency"**
```bash
./gradlew --refresh-dependencies build
```

**"Java version mismatch"**
```bash
# Ensure JAVA_HOME points to Java 21+
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### Test Failures

**"API key not found"**
- Ensure `local.properties` exists with valid keys
- Check key format matches provider requirements

**"Connection refused"**
- Check network connectivity
- Verify API key hasn't expired

### iOS Build Issues

**"No signing identity"**
1. Run `kdoctor --team-ids`
2. Update `ampere-ios/Configuration/Config.xcconfig` with your Team ID
3. Open in Xcode and configure signing

---

## See Also

- [AGENTS.md](AGENTS.md) — AI-assisted development guide
- [CLI Reference](ampere-cli/README.md) — Using the CLI tools
