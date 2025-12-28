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

## Commands

### Interactive Dashboard (Default)

Start the interactive multi-modal dashboard:

```bash
./ampere-cli/ampere              # Same as 'ampere start'
./ampere-cli/ampere start        # Interactive multi-modal dashboard
```

**Keyboard controls while dashboard is running:**
- `d` - Dashboard mode (system vitals, agent status, recent events)
- `e` - Event stream mode (filtered event stream)
- `m` - Memory operations mode (knowledge recall/storage patterns)
- `v` - Toggle verbose mode (show/hide routine events)
- `h` or `?` - Toggle help screen
- `:` - Command mode (issue commands to the system)
- `ESC` - Close help / Cancel command mode
- `1-9` - Agent focus mode (detailed view of specific agent)
- `q` or `Ctrl+C` - Exit

**Command mode (press `:` while in dashboard):**
- `:help` - Show available commands
- `:agents` - List all active agents
- `:ticket <id>` - Show ticket details
- `:thread <id>` - Show conversation thread
- `:quit` - Exit dashboard

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

### Test Commands

Run tests and demonstrations:

```bash
./ampere-cli/ampere test jazz                          # Autonomous agent demo (Fibonacci)
./ampere-cli/ampere test ticket                        # GitHub issue creation demo
./ampere-cli/ampere jazz-test                          # Legacy command (same as test jazz)
```

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
