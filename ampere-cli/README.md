# Ampere CLI Documentation

**Ampere CLI** provides command-line tools for observing and managing the Ampere agent substrate, including real-time event streaming, thread management, system status monitoring, and execution outcome analysis.

## Prerequisites

- **Java 21** or higher is required to run the CLI
- Check your Java version: `java -version`
- If you have multiple Java versions, you can list them: `/usr/libexec/java_home -V`

## Building the CLI

```bash
./gradlew :ampere-cli:installJvmDist
```

The executable will be located at:
```
ampere-cli/build/install/ampere-cli-jvm/bin/ampere-cli
```

## Running with Java 21

### Option 1: Use the wrapper script (recommended)

A wrapper script is provided that automatically uses Java 21:

```bash
./ampere-cli/ampere <command>
```

### Option 2: Set JAVA_HOME manually

If your default Java version is not 21+, set JAVA_HOME before running:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
ampere-cli/build/install/ampere-cli-jvm/bin/ampere-cli <command>
```

Or inline:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ampere-cli/build/install/ampere-cli-jvm/bin/ampere-cli <command>
```
