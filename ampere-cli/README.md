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

---

## Interactive Mode

**The recommended way to use the AMPERE CLI** is through interactive mode, which provides a persistent session where you can observe and interact with the substrate.

### Starting Interactive Mode

```bash
# Launch interactive session (default when no arguments provided)
./ampere-cli/ampere

# Or explicitly specify interactive mode
./ampere-cli/ampere interactive
```

### Interactive Session Features

- **Persistent Environment**: AmpereContext stays alive across commands
- **Interruptible Commands**: Press Ctrl+C to stop observations without exiting
- **Command History**: Use ↑/↓ arrows to recall previous commands
- **Tab Completion**: Press Tab for command suggestions (coming soon)
- **Multiple Commands**: Run any observation command in sequence

### Example Session

```bash
$ ./ampere-cli/ampere

╔═══════════════════════════════════════════════════════╗
║  AMPERE Interactive Shell v0.1.0                      ║
║  Autonomous Multi-Process Execution & Relay Env       ║
║                                                       ║
║  Type 'help' for commands, 'exit' to quit            ║
║  Press Ctrl+C to interrupt running observations       ║
╚═══════════════════════════════════════════════════════╝

System Status: ● Running

ampere> watch --filter TaskCreated
[Streaming events... Press Ctrl+C to stop]
14:32:18  📋  TaskCreated  Task #123: Implement auth
^C
[Command interrupted]

ampere> status
[Shows system dashboard]

ampere> thread list
[Shows all threads]

ampere> exit
Goodbye! Shutting down environment...
```

### Available Commands in Interactive Mode

All observation commands work in interactive mode:

- **watch** - Stream events in real-time
- **status** - Show system dashboard
- **thread list/show** - View conversations
- **outcomes** - Query execution memory

Action commands (coming soon):

- **ticket create** - Create new tickets
- **message post** - Post to threads
- **agent wake** - Trigger agent activity

---

## Command Reference

### Overview

```
ampere (root)
├── watch                 # Real-time event streaming
├── thread                # Thread management
│   ├── list             # List all threads
│   └── show             # Show thread details
├── status               # System-wide dashboard
└── outcomes             # Execution outcome memory
    ├── ticket           # Show ticket history
    ├── search           # Find similar outcomes
    ├── executor         # Executor performance
    └── stats            # Aggregate statistics
```

---

## `ampere watch`

**Observe the substrate's electrical activity in real-time** by streaming events from the EventBus.

### Basic Usage

```bash
# Watch all events
./ampere-cli/ampere watch

# Filter by event type
./ampere-cli/ampere watch --filter TaskCreated

# Filter by agent
./ampere-cli/ampere watch --agent agent-pm

# Combine filters
./ampere-cli/ampere watch --filter TaskCreated --agent agent-pm

# Multiple filters (short options)
./ampere-cli/ampere watch -f TaskCreated -f QuestionRaised -a agent-pm
```

### Options

- `--filter, -f` (repeatable): Filter by event type (case-insensitive)
- `--agent, -a` (repeatable): Filter by agent ID

### Available Event Types

**Base Events:**
- `TaskCreated` - New tasks in the system
- `QuestionRaised` - Questions requiring attention
- `CodeSubmitted` - Code submitted for review

**Meeting Events:**
- `MeetingScheduled` - Meeting scheduled
- `MeetingStarted` - Meeting started
- `AgendaItemStarted` - Agenda item started
- `AgendaItemCompleted` - Agenda item completed
- `MeetingCompleted` - Meeting completed
- `MeetingCanceled` - Meeting canceled

**Ticket Events:**
- `TicketCreated` - New ticket created
- `TicketStatusChanged` - Ticket status updated
- `TicketAssigned` - Ticket assigned to agent
- `TicketBlocked` - Ticket blocked
- `TicketCompleted` - Ticket completed
- `TicketMeetingScheduled` - Meeting scheduled for ticket

**Message Events:**
- `ThreadCreated` - New conversation thread
- `MessagePosted` - Message posted to thread
- `ThreadStatusChanged` - Thread status changed
- `EscalationRequested` - Escalation to human requested

**Notification Events:**
- `NotificationToAgent` - Notification sent to agent
- `NotificationToHuman` - Notification sent to human

### Output Format

Events are displayed with color coding and icons:

```
[timestamp] [icon] [event type]  [summary]
```

**Example:**
```
14:32:18  📋  TaskCreated              Task #123: Implement authentication (assigned to: agent-auth) [HIGH] from agent-pm
14:32:25  ❓  QuestionRaised           "How should we handle this error?" - Context: During database migration [MEDIUM] from agent-dev
14:33:01  💻  CodeSubmitted            src/main/Auth.kt - Add OAuth support (review required) for agent-reviewer [LOW] from agent-dev
```

**Color Coding:**
- **Green** (📋🎫): Tasks and tickets (actionable items)
- **Magenta** (❓📅): Questions and meetings (need attention)
- **Cyan** (💻): Code submissions (technical)
- **Blue** (💬): Messages (communication)
- **White** (🔔): Notifications (informational)
- **Red** [HIGH]: Urgent priority
- **Yellow** [MEDIUM]: Medium priority
- **Gray** [LOW]: Low priority

---

## `ampere thread`

**View and manage conversation threads** in the substrate.

### `ampere thread list`

List all active threads with summary information.

```bash
# List all threads (table format)
./ampere-cli/ampere thread list

# List threads as JSON
./ampere-cli/ampere thread list --json
./ampere-cli/ampere thread list -j
```

**Output includes:**
- Thread ID
- Message count
- Participants
- Last activity timestamp
- Current status

### `ampere thread show`

Display the complete conversation history for a specific thread.

```bash
# Show thread details
./ampere-cli/ampere thread show <thread-id>

# Show thread as JSON
./ampere-cli/ampere thread show <thread-id> --json
```

**Arguments:**
- `thread-id`: The ID of the thread to display

**Options:**
- `--json, -j`: Output as JSON

**Output includes:**
- All messages in chronological order
- Speaker identification
- Timestamps
- Message content

---

## `ampere status`

**Display a comprehensive dashboard** of system state for situational awareness.

```bash
# Human-readable dashboard
./ampere-cli/ampere status

# JSON output for scripting
./ampere-cli/ampere status --json
./ampere-cli/ampere status -j
```

**Dashboard Sections:**

**Thread Metrics:**
- Total active threads
- Threads with escalations
- Stale threads (>24 hours old)

**Ticket Metrics:**
- Total active tickets
- Status breakdown (InProgress, Todo, Blocked)
- Unassigned tickets
- High priority tickets

**Urgent Items:**
- Items requiring immediate attention

**Options:**
- `--json, -j`: Output as JSON for automation/scripting

---

## `ampere outcomes`

**View execution outcomes and accumulated experience** from the substrate's memory system.

### `ampere outcomes ticket`

Show all execution attempts for a specific ticket (useful for debugging failing tickets).

```bash
./ampere-cli/ampere outcomes ticket <ticket-id>
```

**Arguments:**
- `ticket-id`: ID of the ticket to query

**Output:**
- Timestamp of each attempt
- Executor ID
- Result (success/failure)
- Duration
- Files changed
- Error messages (if failed)

**Use Case:** Debugging why a ticket keeps failing or understanding its execution history.

---

### `ampere outcomes search`

Find outcomes similar to a description ("Has anyone tried something like this before?").

```bash
# Search for similar outcomes
./ampere-cli/ampere outcomes search <query>

# Limit results
./ampere-cli/ampere outcomes search <query> --limit 5
./ampere-cli/ampere outcomes search <query> -n 5
```

**Arguments:**
- `query`: Search query with keywords from ticket description

**Options:**
- `--limit, -n` (default: 10): Maximum number of results to return

**Output:**
- Success status
- Ticket ID
- Executor ID
- Approach taken
- Error details (if failed)

**Use Case:** Learning from past attempts before trying a new approach.

---

### `ampere outcomes executor`

Show outcomes for a specific executor (useful for analyzing performance).

```bash
# Analyze executor performance
./ampere-cli/ampere outcomes executor <executor-id>

# Show more recent outcomes
./ampere-cli/ampere outcomes executor <executor-id> --limit 50
```

**Arguments:**
- `executor-id`: ID of the executor to analyze

**Options:**
- `--limit, -n` (default: 20): Maximum number of recent outcomes to show

**Output:**
- Success rate percentage
- Average execution duration
- Table of recent outcomes with details

**Use Case:** Identifying which executors are most reliable for specific task types.

---

### `ampere outcomes stats`

Show aggregate outcome statistics and system learning metrics.

```bash
./ampere-cli/ampere outcomes stats
```

**Planned Features:**
- Overall success rate across all executions
- Success rate by executor
- Success rate by ticket category
- Trends over time

**Status:** TODO (placeholder implementation)

---

## General Options

All commands support:

- `--help`: Display help for the command
- `--json, -j` (where applicable): Output as JSON for scripting and automation

---

## Troubleshooting

### No events appearing in watch

- Make sure events are being published to the EventBus
- Check that your filters match the events being published
- Verify event types are spelled correctly (case-insensitive)

### Invalid event type warning

Check the spelling against the [Available Event Types](#available-event-types) list above.

### Java version errors

- Verify Java 21+ is installed: `java -version`
- Use the wrapper script: `./ampere-cli/ampere`
- Or set JAVA_HOME manually (see [Running with Java 21](#running-with-java-21))

### Command not found

- Ensure you've built the CLI: `./gradlew :ampere-cli:installJvmDist`
- Use the full path: `./ampere-cli/build/install/ampere-cli-jvm/bin/ampere-cli`
- Or use the wrapper: `./ampere-cli/ampere`

---

## Advanced Usage

### Scripting with JSON Output

Most commands support `--json` output for integration with scripts:

```bash
# Export thread list to file
./ampere-cli/ampere thread list --json > threads.json

# Parse with jq
./ampere-cli/ampere status --json | jq '.tickets.unassigned'

# Monitor specific events programmatically
./ampere-cli/ampere watch --filter TicketBlocked --json | while read -r event; do
  echo "$event" | jq '.ticketId'
done
```

### Continuous Monitoring

Keep watch running in the background and filter critical events:

```bash
# Monitor high-priority issues
./ampere-cli/ampere watch --filter TicketBlocked --filter EscalationRequested &

# Tail the output
tail -f watch.log
```

### Combining Commands

Use multiple terminals to observe different aspects:

```bash
# Terminal 1: Watch all events
./ampere-cli/ampere watch

# Terminal 2: Monitor system status
watch -n 5 ./ampere-cli/ampere status

# Terminal 3: Track specific thread
./ampere-cli/ampere thread show thread-123
```

---

## Architecture

The Ampere CLI uses:

- **Clikt** - Command-line interface toolkit
- **Mordant** - Terminal rendering with colors and tables
- **SQLite/SQLDelight** - Event and outcome persistence
- **Coroutines** - Async event streaming

All commands connect to the shared Ampere substrate database and event bus.

---

## Getting Help

For detailed help on any command:

```bash
./ampere-cli/ampere --help
./ampere-cli/ampere watch --help
./ampere-cli/ampere thread --help
./ampere-cli/ampere status --help
./ampere-cli/ampere outcomes --help
```

---

## Related Documentation

- [CLAUDE.md](../CLAUDE.md) - Project architecture and development guide
