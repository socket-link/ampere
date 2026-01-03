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
./ampere-cli/ampere run --demo jazz          # Fibonacci demo

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
./ampere-cli/ampere test jazz                          # Headless Jazz test (Fibonacci)
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

The CLI uses the Ampere database located at `~/ampere.db` by default. Logging can be configured via the `AMPERE_LOG_LEVEL` environment variable.

## GitHub Authentication

For the `issues` command, you must be authenticated with the GitHub CLI:

```bash
gh auth login
```

Verify your authentication:

```bash
gh auth status
```
