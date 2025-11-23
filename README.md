[![Maven Central](https://img.shields.io/maven-central/v/link.socket.ampere/ampere-client?color=blue&label=Download)](https://central.sonatype.com/namespace/link.socket.ampere)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# Ampere: A KMP Library for AI Agents & Assistants

<img src="readme_images/banner.png" height="450">

> **Note**  
> This library, its APIs, and the sample client applications are in Alpha.
> It may change incompatibly and require manual migration in the future.
> If you have any issues, please report them on [GitHub](https://github.com/socket-link/ampere/issues).

## 📔 Overview

Ampere provides a Kotlin Multiplatform library for creating & managing **Agent** and **Assistant** chatbot definitions.

### Core Components

- **Agents**: Specialized AI chatbots with domain-specific knowledge for answering complex prompts
- **Assistants**: Meta-Agents that plan complex tasks and delegate work to specialized Agents

### Dependencies

- [`openai-kotlin`](https://github.com/aallam/openai-kotlin/tree/main) - LLM functionality using OpenAI
- [`turtle`](https://github.com/lordcodes/turtle) - Shell script capabilities

## 🥷🏻 Agents

An **Agent** is a specialized AI Chatbot with domain-specific knowledge for well-defined tasks.

### Configuration
- **System Prompt**: Defines the Agent's mindset and approach
- **User Prompt**: Initial prompt with optional `HumanAssisted` API for dynamic input

### [Bundled Agents](https://github.com/socket-link/ampere/tree/main/shared/src/commonMain/kotlin/link/socket/ampere/model/agent/bundled)

- Save File Agent
- Modify File Agent  
- Generate Code Agent
- Fix JSON Agent
- Generate Sub-Agent Agent
- Family Agent (example implementation)

## 🧑🏻‍🏫 Assistants

> **Note**  
> Assistant capabilities are not yet integrated. Stay tuned for this essential framework functionality.

**Assistants** are specialized **Agents** with `System Prompts` for task delegation and spawning **Sub-Agents** for specialized responses.

## 📦 Prerequisites

### Required Software
- Mac running recent macOS
- [Xcode](https://apps.apple.com/us/app/xcode/id497799835)
- [Android Studio](https://developer.android.com/studio)
- [Kotlin Multiplatform Mobile plugin](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform-mobile)

## ⚡️ Quick Start

Use [KDoctor](https://github.com/Kotlin/kdoctor) to verify your setup:

```bash
# Install KDoctor
brew install kdoctor

# Verify environment
kdoctor
```

1. Open the project in Android Studio
2. Switch from **Android** to **Project** view
3. Explore the main modules:
    - `shared`: Common logic and Compose Multiplatform code
    - `desktopApp`: Desktop application target
    - `androidApp`: Android application target
    - `iosApp`: Xcode project for iOS

### Environment Variables
- Add your AI providers' API keys to `local.properties`: 
```
anthropic_api_key=$YOUR_KEY
google_api_key=$YOUR_KEY
openai_api_key=$YOUR_KEY
```
- You can generate API keys for these providers at the following links:
- [Anthropic API Keys](https://console.anthropic.com/settings/keys)
- [Google API Keys](https://aistudio.google.com/app/apikey)
- [OpenAI API Keys](https://platform.openai.com/account/api-keys)

## 🚀 Running Applications

### Desktop

**IDE**: Use `desktopApp` run configuration in Android Studio

**Command Line**:
```bash
# Run application
./gradlew run

# Package for distribution
./gradlew package  # Output: build/compose/binaries
```

### Android

**IDE**: 
1. Select `androidApp` run configuration
2. Choose device/emulator
3. Click **Run**

**Command Line**:
```bash
./gradlew installDebug
```

### iOS

**IDE**:
1. Configure `iosApp` run configuration
2. Select target device
3. Click **Run**

**Physical Device Setup**:
1. Find your `TEAM_ID`: `kdoctor --team-ids`
2. Set `TEAM_ID` in `iosApp/Configuration/Config.xcconfig`
3. Register device in Xcode
4. Run `iosApp` configuration

For detailed device setup, see [Apple's documentation](https://developer.apple.com/documentation/xcode/running-your-app-on-a-device).
