# Ampere CLI Command Reference

This document provides a comprehensive reference for all CLI commands, their behavior, flags, and valid parameter values.

## Command Overview

Running `ampere` with no arguments launches the **interactive TUI dashboard** - a 3-column terminal interface for observing agent cognition in real-time.

The CLI provides multiple modes of operation:
1. **Interactive TUI** (default): `ampere` - Rich 3-column visualization with animated substrate
2. **Goal Mode**: `ampere --goal "..."` - Start TUI and immediately assign a goal
3. **Work Mode**: `ampere --issues` or `ampere --issue 42` - Work on GitHub issues
4. **Command Mode**: `ampere <command>` - Direct command execution (e.g., `ampere status`)

---

## OBSERVATION COMMANDS

These commands let you observe the agent system's activity.

### status

**Purpose:** Display comprehensive system dashboard showing current state

**Syntax:** `status`

**Flags:** None

**Behavior:**
- Fetches data concurrently from multiple services
- Displays:
  - **Threads**: Total active, escalations count, stale threads (>24h)
  - **Tickets**: Total active, breakdown by status, unassigned count, high-priority count
  - **Urgent Items**: Summary of items needing human attention
- Color-coded output:
  - Green: Healthy/active
  - Yellow: Warnings (stale, unassigned)
  - Red: Critical (escalations, blocked tickets)
- Shows "All systems nominal" if no urgent items

**Example:**
```bash
ampere status
```

**Sample Output:**
```
⚡ AMPERE System Status

📝 Threads
  Total: 3 active
  ⚠ 1 with escalations
  ⏰ 1 stale (>24h since activity)

🎫 Tickets
  Total: 5 active
    InProgress: 2
    Todo: 2
    Blocked: 1
  ⚠ 1 unassigned
  🔥 1 high priority

⚠ Needs Attention:
  • 1 thread(s) need human attention
  • 1 ticket(s) blocked
```

**Implementation:** `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/StatusCommand.kt`

---

### thread

**Purpose:** View conversation threads in the environment

**Syntax:** `thread <subcommand> [args]`

**Subcommands:**
- `list` - List all active threads
- `show <thread-id>` - Display full thread conversation

---

#### thread list

**Purpose:** List all active conversation threads with summary information

**Syntax:** `thread list`

**Flags:** None

**Behavior:**
- Shows table with: thread ID, title, message count, participant count, last activity
- Highlights threads with unread escalations
- Sorted by last activity (most recent first)

**Example:**
```bash
ampere thread list
```

**Implementation:** `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/ThreadCommand.kt:35`

---

#### thread show

**Purpose:** Display complete conversation history for a specific thread

**Syntax:** `thread show <thread-id>`

**Arguments:**
- `thread-id` - ID of the thread to display (required)

**Behavior:**
- Shows thread title and participants
- Displays all messages with timestamps and sender identification
- Messages shown in chronological order
- Error if thread ID not found

**Example:**
```bash
ampere thread show thread-abc123
```

**Implementation:** `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/ThreadCommand.kt:70`

---

### outcomes

**Purpose:** Query execution outcome memory and accumulated experience

**Syntax:** `outcomes <subcommand> [args]`

**Subcommands:**
- `ticket <ticket-id>` - Show execution history for a ticket
- `search <query>` - Find outcomes similar to a description
- `executor <executor-id>` - Show outcomes for a specific executor
- `stats` - Show aggregate outcome statistics

---

#### outcomes ticket

**Purpose:** Show all execution attempts for a specific ticket (useful for debugging repeated failures)

**Syntax:** `outcomes ticket <ticket-id>`

**Arguments:**
- `ticket-id` - ID of the ticket to query (required)

**Behavior:**
- Shows execution history table with: timestamp, executor, result, duration, files changed, error
- Results color-coded: green ✓ for success, red ✗ for failure
- Error messages truncated to 40 characters
- "No execution attempts found" if ticket has no history

**Example:**
```bash
ampere outcomes ticket TKT-123
```

**Implementation:** `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/OutcomesCommand.kt:50`

---

#### outcomes search

**Purpose:** Find outcomes similar to a description (learning query: "Has anyone tried this before?")

**Syntax:** `outcomes search <query> [--limit N]`

**Arguments:**
- `query` - Search keywords from ticket description (required)

**Flags:**
- `-n, --limit N` - Maximum number of results (default: 10)

**Behavior:**
- Searches outcome descriptions using keyword matching
- Shows: result indicator, ticket ID, executor ID, approach summary
- Displays error messages for failed outcomes
- Limited to specified number of results

**Example:**
```bash
ampere outcomes search "authentication"
ampere outcomes search "API integration" --limit 20
```

**Implementation:** `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/OutcomesCommand.kt:138`

---

#### outcomes executor

**Purpose:** Analyze performance of a specific executor

**Syntax:** `outcomes executor <executor-id> [--limit N]`

**Arguments:**
- `executor-id` - ID of the executor to analyze (required)

**Flags:**
- `-n, --limit N` - Maximum number of outcomes to show (default: 20)

**Behavior:**
- Calculates statistics: success rate, average duration
- Shows recent outcomes table with: timestamp, ticket, result, duration, files changed
- Displays performance summary at top

**Example:**
```bash
ampere outcomes executor agent-dev
ampere outcomes executor agent-pm --limit 50
```

**Implementation:** `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/OutcomesCommand.kt:194`

---

#### outcomes stats

**Purpose:** Show aggregate statistics about all outcomes

**Syntax:** `outcomes stats`

**Flags:** None

**Behavior:**
- Currently a placeholder showing planned metrics:
  - Overall success rate
  - Success rate by executor
  - Success rate by ticket category
  - Trends over time

**Example:**
```bash
ampere outcomes stats
```

**Implementation:** `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/OutcomesCommand.kt:277`

---

## ACTION COMMANDS

These commands create work and coordinate agents. (Note: These are designed for the REPL mode and may not be fully implemented as standalone CLI commands yet)

### ticket

**Purpose:** Manage tickets in the system

**Available Operations:**
- `create "TITLE" [--priority PRI] [--description "DESC"]` - Create new ticket
- `assign <ticket-id> <agent-id>` - Assign ticket to an agent
- `status <ticket-id> <STATUS>` - Update ticket status

**Valid Ticket Priorities:**
- `LOW` - Low priority
- `MEDIUM` - Medium priority (default)
- `HIGH` - High priority
- `CRITICAL` - Critical priority

**Valid Ticket Statuses:**
- `Backlog` - In backlog, not yet prioritized
- `Ready` - Ready to be picked up
- `In Progress` / `InProgress` - Actively being worked on
- `Blocked` - Blocked by dependencies/issues
- `In Review` / `InReview` - Awaiting review
- `Done` - Complete

**Valid Status Transitions:**
- Backlog → Ready, Done
- Ready → In Progress
- In Progress → Blocked, In Review, Done
- Blocked → In Progress
- In Review → In Progress, Done
- Done → (terminal state)

**Examples:**
```bash
ticket create "Implement user authentication" --priority HIGH
ticket create "Fix navbar styling" -p MEDIUM -d "Navbar overlaps content on mobile"
ticket assign TKT-123 agent-dev
ticket status TKT-123 InProgress
ticket status TKT-123 InReview
```

---

### message

**Purpose:** Manage messages and conversation threads

**Available Operations:**
- `post <thread-id> "TEXT" [--sender ID]` - Post message to thread
- `create-thread --title "TITLE" --participants A,B` - Create new thread

**Examples:**
```bash
message post thread-123 "Hello team"
message post thread-123 "Status update" --sender agent-pm
message create-thread --title "Planning Discussion" --participants agent-pm,agent-dev
```

---

### agent

**Purpose:** Interact with agents

**Available Operations:**
- `wake <agent-id>` - Send wake signal to dormant agent

**Examples:**
```bash
agent wake agent-pm
agent wake agent-dev
```

---

## TUI MODE (Default)

The default mode when running `ampere` with no arguments. A rich 3-column terminal interface with animated substrate visualization for observing agent cognition.

**Launch:**
```bash
ampere                    # Launches TUI dashboard (default)
ampere --goal "..."       # Start with a goal
ampere --auto-work        # Start with background issue work
ampere --issues           # Work on GitHub issues
ampere --issue 42         # Work on specific issue
ampere --arc devops-pipeline  # Use a specific arc workflow
```

**TUI Layout:**
- **Left pane (35%)**: Event stream (filtered by significance)
- **Middle pane (40%)**: Cognitive cycle progress / system vitals
- **Right pane (25%)**: Agent memory stats (or logs in verbose mode)

**Keyboard Controls:**
- `d` - Dashboard mode (system vitals, agent status)
- `e` - Event stream mode (filtered events)
- `m` - Memory operations mode (knowledge patterns)
- `v` - Toggle verbose mode (show/hide logs)
- `h` or `?` - Toggle help screen
- `:` - Command mode (issue commands like `:goal`, `:ticket`)
- `1-9` - Focus on specific agent
- `ESC` - Exit focus/help
- `q` or `Ctrl+C` - Exit TUI

**Command Mode (press `:`):**
- `:goal <description>` - Start agent with goal
- `:agents` - List all active agents
- `:ticket <id>` - Show ticket details
- `:thread <id>` - Show conversation thread
- `:quit` - Exit TUI

---

## GLOBAL OPTIONS

**Flags available on the root `ampere` command:**
- `--help, -h` - Show help and exit
- `--version` - Show version information (if implemented)

---

## COMMAND RESOLUTION

When running `ampere` with no arguments:
1. Launches interactive TUI dashboard with animated substrate
2. Press `q` or `Ctrl+C` to exit

When running `ampere --goal "..."` or `ampere -g "..."`:
1. Launches TUI and immediately assigns the goal to agents
2. Agent cognitive cycle is visualized in real-time

When running `ampere <command>`:
1. Parses command via Clikt framework
2. Executes command
3. Exits after completion

---

## ERROR HANDLING

**Common Errors:**
- **Unknown command**: "Unknown command: xyz. Type 'help' for available commands"
- **Invalid event type**: Warning displayed, filter ignored
- **Thread not found**: "Thread not found: thread-id"
- **Invalid ticket status**: "Invalid ticket status: xyz"
- **Missing required argument**: Clikt displays usage and error

**Exit Codes:**
- `0` - Success
- `1` - Error (general)

---

## DEPENDENCIES

**Core Libraries:**
- **Clikt** - Command-line parsing framework
- **Mordant** - Terminal rendering (colors, tables, styles)
- **JLine** - REPL terminal handling (readline)
- **Kotlin Coroutines** - Async command execution

**Services Used:**
- `EventRelayService` - Event streaming and subscription
- `ThreadViewService` - Thread querying
- `TicketViewService` - Ticket querying
- `OutcomeMemoryRepository` - Outcome persistence and search

---

## FILES

**Command Implementations:**
- `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/AmpereCommand.kt`
- `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/StatusCommand.kt`
- `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/ThreadCommand.kt`
- `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/OutcomesCommand.kt`

**Main Entry:**
- `/home/user/ampere/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/Main.kt`

---

## NOTES

1. **Event Types**: Retrieved dynamically from `EventRegistry` - new event types are automatically available
2. **Runtime IDs**: Agent IDs, thread IDs, and ticket IDs are determined at runtime and can be discovered via `status` and `thread list` commands
3. **Case Sensitivity**: Event type filtering is case-insensitive
4. **Repeatability**: `--filter` and `--agent` flags can be used multiple times for OR filtering
5. **Color Support**: Output uses ANSI colors when terminal supports it
6. **Terminal Width**: Help adapts to terminal width (compact mode < 90 chars)
