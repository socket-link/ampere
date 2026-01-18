# Ampere CLI Documentation

**Ampere CLI** provides command-line tools for running and observing AI agents in the Ampere substrate. The CLI features:
- **Interactive TUI**: Beautiful 3-column terminal UI for real-time visualization
- **Active Work Modes**: Run agents on custom goals, demos, or GitHub issues
- **Observation Tools**: Event streaming, thread management, system monitoring
- **Headless Testing**: Automated validation for CI/CD pipelines

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

## Quick Start

```bash
# Start the interactive TUI dashboard
./ampere-cli/ampere              # or 'ampere start'

# Run an agent with a custom goal (with TUI)
./ampere-cli/ampere run --goal "Implement FizzBuzz"

# Run the Jazz demo (with TUI)
./ampere-cli/ampere run --demo jazz

# Work on GitHub issues (with TUI)
./ampere-cli/ampere run --issues

# Run headless tests (CI/validation)
./ampere-cli/ampere test jazz
```

## Commands

### Start: Interactive TUI Dashboard

Start the interactive 3-column TUI for observing the AMPERE system:

```bash
./ampere-cli/ampere              # Default: starts TUI dashboard
./ampere-cli/ampere start        # Explicit: same as above
./ampere-cli/ampere start --auto-work   # Start with background issue work
```

The TUI shows:
- **Left pane (35%)**: Event stream (filtered by significance)
- **Middle pane (40%)**: Cognitive cycle progress / system vitals
- **Right pane (25%)**: Agent memory stats (or logs in verbose mode)

**Keyboard controls:**
- `d` - Dashboard mode (system vitals, agent status)
- `e` - Event stream mode (filtered events)
- `m` - Memory operations mode (knowledge patterns)
- `v` - Toggle verbose mode (show/hide logs in right pane)
- `h` or `?` - Toggle help screen
- `:` - Command mode (issue commands)
- `ESC` - Close help / Cancel command mode
- `1-9` - Agent focus mode (detailed agent view)
- `q` or `Ctrl+C` - Exit

**Command mode (press `:`):**
- `:goal <description>` - Start agent with goal
- `:help` - Show available commands
- `:agents` - List all active agents
- `:ticket <id>` - Show ticket details
- `:thread <id>` - Show conversation thread
- `:quit` - Exit TUI

### Run: Active Work with TUI Visualization

Run agents with active work while visualizing progress in real-time:

```bash
# Run agent with custom goal
./ampere-cli/ampere run --goal "Implement FizzBuzz function"
./ampere-cli/ampere run -g "Add authentication to API"

# Run preset demos
./ampere-cli/ampere run --demo jazz          # Task-create command demo

# Work on GitHub issues
./ampere-cli/ampere run --issues             # Continuous issue work
./ampere-cli/ampere run --issue 42           # Specific issue
```

The `run` command uses the same TUI as `start` but activates an agent to work
on the specified task. You can watch the agent's cognitive cycle in real-time.

### Watch Command

Stream events in real-time with filtering:

```bash
./ampere-cli/ampere watch
./ampere-cli/ampere watch --verbose                    # Show all events including routine
./ampere-cli/ampere watch --group-cognitive-cycles     # Group knowledge operations
./ampere-cli/ampere watch --filter TaskCreated --agent agent-pm
```

### Dashboard Command

Static live-updating dashboard (non-interactive):

```bash
./ampere-cli/ampere dashboard
./ampere-cli/ampere dashboard --refresh-interval 2     # Update every 2 seconds
```

### Thread Command

View and manage conversation threads:

```bash
./ampere-cli/ampere thread list                        # List all threads
./ampere-cli/ampere thread show <thread-id>            # Show thread details
```

### Status Command

System-wide status overview:

```bash
./ampere-cli/ampere status
```

### Outcomes Command

View execution outcomes and accumulated experience:

```bash
./ampere-cli/ampere outcomes ticket <ticket-id>        # Show execution history for a ticket
./ampere-cli/ampere outcomes search <query>            # Find outcomes similar to a query
./ampere-cli/ampere outcomes executor <executor-id>    # Show outcomes for a specific executor
./ampere-cli/ampere outcomes stats                     # Show aggregate outcome statistics
```

### Issues Command

Manage GitHub issues with batch creation from JSON:

```bash
# Create issues from file
./ampere-cli/ampere issues create -f .ampere/issues/pm-agent-epic.json

# Create from stdin (useful for piping)
cat pm-agent-epic.json | ./ampere-cli/ampere issues create --stdin

# Validate JSON without creating issues (dry run)
./ampere-cli/ampere issues create -f epic.json --dry-run
```

**JSON Format:**

Issues are defined in JSON files following the `BatchIssueCreateRequest` format:

```json
{
  "repository": "owner/repo",
  "issues": [
    {
      "localId": "unique-id",
      "type": "Feature",
      "title": "Issue title",
      "body": "Issue description in markdown",
      "labels": ["label1", "label2"],
      "assignees": [],
      "parent": null,
      "dependsOn": []
    },
    {
      "localId": "task-1",
      "type": "Task",
      "title": "Subtask title",
      "body": "Task description",
      "labels": ["task"],
      "parent": "unique-id",
      "dependsOn": []
    }
  ]
}
```

**Supported issue types:** `Feature`, `Task`, `Bug`, `Spike`

**Features:**
- Hierarchical issue creation (epics containing tasks)
- Dependency tracking between issues
- Topological sorting ensures dependencies and parents are created first
- Parent-child relationships documented in issue bodies
- Dependency references added to issue descriptions
- Dry-run mode for validation before creation

**File Organization:**

Store issue definitions in `.ampere/issues/` for easy reference:

```
.ampere/
└── issues/
    ├── pm-agent-epic.json
    ├── coordination-viz.json
    └── ...
```

### Interactive Command

Launch an interactive REPL session:

```bash
./ampere-cli/ampere interactive
```

### Test: Headless Validation

Run headless tests for automated validation (CI/CD):

```bash
./ampere-cli/ampere test jazz                          # Headless Jazz test (task-create command)
./ampere-cli/ampere test ticket                        # Headless issue creation test
```

**Note:** These run without interactive UI, suitable for CI pipelines. For
interactive demos with visual feedback, use `ampere run --demo <name>` instead.

### Respond Command

Respond to agent human input requests:

```bash
./ampere-cli/ampere respond <request-id> "Your response"
```

## Configuration

AMPERE uses YAML configuration files to define your AI provider, team composition, and agent personalities. This is the primary method for configuring your agent environment.

### Quick Start

1. Copy the example configuration:
   ```bash
   cp ampere.example.yaml ampere.yaml
   ```

2. Edit `ampere.yaml` with your settings

3. Run the CLI:
   ```bash
   ./ampere-cli/ampere run --goal "Your task description"
   ```

### Configuration File Locations

The CLI automatically loads configuration from these locations (in order of precedence):

1. `--config <path>` — Explicit path via command-line flag
2. `ampere.yaml` — Current directory
3. `ampere.yml` — Current directory
4. `.ampere/config.yaml` — Hidden config directory
5. `.ampere/config.yml` — Hidden config directory

### Configuration Format

```yaml
# AI Provider Configuration
ai:
  provider: anthropic           # anthropic, openai, or gemini
  model: sonnet-4               # Model name (see list below)

  # Optional: Fallback providers (tried in order if primary fails)
  backups:
    - provider: openai
      model: gpt-4.1
    - provider: gemini
      model: flash-2.5

# Team Composition
team:
  - role: product-manager
    personality:
      directness: 0.8           # 0=diplomatic, 1=very direct
      thoroughness: 0.7
      formality: 0.6

  - role: engineer
    personality:
      creativity: 0.7           # 0=conventional, 1=creative
      risk-tolerance: 0.4       # 0=conservative, 1=risk-taking

  - role: qa-tester
    # Uses default personality

# Optional: Default goal (can also pass via --goal flag)
goal: "Build a user authentication system"
```

### AI Providers and Models

#### Anthropic (Claude)

```yaml
ai:
  provider: anthropic
  model: sonnet-4    # Recommended for most tasks
```

**Available models:**
| Model | Best For |
|-------|----------|
| `opus-4.5` | Complex reasoning, research |
| `opus-4.1` | Complex tasks |
| `opus-4` | Complex tasks |
| `sonnet-4.5` | Balanced performance |
| `sonnet-4` | General purpose (recommended) |
| `sonnet-3.7` | Cost-effective |
| `haiku-4.5` | Fast, simple tasks |
| `haiku-3.5` | Fast, simple tasks |
| `haiku-3` | Fastest, basic tasks |

#### OpenAI (GPT)

```yaml
ai:
  provider: openai
  model: gpt-4.1    # Recommended
```

**Available models:**
| Model | Best For |
|-------|----------|
| `gpt-5.1` | Most capable |
| `gpt-5` | High capability |
| `gpt-5-mini` | Balanced |
| `gpt-5-nano` | Fast, efficient |
| `gpt-4.1` | Reliable (recommended) |
| `gpt-4.1-mini` | Cost-effective |
| `gpt-4o` | Optimized |
| `gpt-4o-mini` | Fast |
| `o4-mini` | Reasoning |
| `o3` | Advanced reasoning |
| `o3-mini` | Fast reasoning |

#### Google (Gemini)

```yaml
ai:
  provider: gemini
  model: flash-2.5    # Recommended for speed
```

**Available models:**
| Model | Best For |
|-------|----------|
| `pro-3` | Most capable |
| `pro-2.5` | High capability |
| `flash-2.5` | Fast (recommended) |
| `flash-2.5-lite` | Fastest |
| `flash-2` | Balanced |
| `flash-2-lite` | Efficient |

### Agent Roles

Configure your team by selecting from these specialized roles:

| Role | Description | Capabilities |
|------|-------------|--------------|
| `product-manager` | Coordinates work, breaks down tasks, makes product decisions | Planning, Delegation |
| `engineer` | Writes production-quality code and implements features | Code Writing, Code Review |
| `qa-tester` | Creates test suites and identifies edge cases | Testing, Code Review |
| `architect` | Designs system architecture and APIs | API Design, Planning |
| `security-reviewer` | Reviews code for security vulnerabilities | Security Review, Code Review |
| `technical-writer` | Creates comprehensive documentation | Documentation |

### Personality Traits

Customize agent behavior with personality traits (all values 0.0 to 1.0):

```yaml
personality:
  directness: 0.5       # 0=diplomatic, 1=very direct
  creativity: 0.5       # 0=conventional, 1=creative
  thoroughness: 0.5     # 0=concise, 1=thorough
  formality: 0.5        # 0=casual, 1=formal
  risk-tolerance: 0.3   # 0=conservative, 1=risk-taking
```

**Trait Guidelines:**

| Trait | Low (0.0-0.3) | Medium (0.4-0.6) | High (0.7-1.0) |
|-------|---------------|------------------|----------------|
| `directness` | Diplomatic, softens feedback | Balanced tact | Very direct, straightforward |
| `creativity` | Conventional patterns | Mix of proven/new | Novel solutions, experimental |
| `thoroughness` | Concise, essentials only | Balanced detail | Comprehensive, exhaustive |
| `formality` | Casual, conversational | Professional | Formal, structured |
| `risk-tolerance` | Safe, proven approaches | Calculated risks | Embraces uncertainty |

### Provider Fallbacks

Configure automatic failover when the primary provider fails:

```yaml
ai:
  provider: anthropic
  model: sonnet-4
  backups:
    - provider: openai
      model: gpt-4.1
    - provider: gemini
      model: flash-2.5
```

When Anthropic fails, OpenAI is tried. If OpenAI fails, Gemini is tried.

### Example Configurations

**Minimal configuration:**
```yaml
ai:
  provider: anthropic
  model: sonnet-4

team:
  - role: engineer
```

**Full team with personalities:**
```yaml
ai:
  provider: anthropic
  model: sonnet-4
  backups:
    - provider: openai
      model: gpt-4.1

team:
  - role: product-manager
    personality:
      directness: 0.8
      thoroughness: 0.7

  - role: engineer
    personality:
      creativity: 0.7
      risk-tolerance: 0.4

  - role: qa-tester

  - role: security-reviewer
    personality:
      thoroughness: 0.9
      risk-tolerance: 0.1

goal: "Build a secure user authentication system with OAuth2 support"
```

**Fast iteration setup:**
```yaml
ai:
  provider: gemini
  model: flash-2.5

team:
  - role: engineer
    personality:
      creativity: 0.8
      thoroughness: 0.4

goal: "Quick prototype of the feature"
```

### Using Configuration with Commands

```bash
# Load from default location (ampere.yaml)
./ampere-cli/ampere run --goal "Implement feature X"

# Load from specific file
./ampere-cli/ampere --config path/to/config.yaml run --goal "Implement feature X"

# Override goal from config file
./ampere-cli/ampere run --goal "Different goal"

# Start TUI with config
./ampere-cli/ampere --config team-config.yaml start
```

### Environment Variables

Additional configuration via environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `AMPERE_LOG_LEVEL` | Logging verbosity (DEBUG, INFO, WARN, ERROR) | INFO |
| `ANTHROPIC_API_KEY` | Anthropic API key | — |
| `OPENAI_API_KEY` | OpenAI API key | — |
| `GOOGLE_API_KEY` | Google/Gemini API key | — |

### Database

The CLI uses a SQLite database located at `~/ampere.db` by default for storing events, knowledge, and outcomes.

## GitHub Authentication

For the `issues` command, you must be authenticated with the GitHub CLI:

```bash
gh auth login
```

Verify your authentication:

```bash
gh auth status
```
