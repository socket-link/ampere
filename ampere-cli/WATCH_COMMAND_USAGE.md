# Watch Command Usage Guide

The `ampere watch` command connects to the event stream and displays events in real-time with color coding and formatting.

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
./ampere-cli/ampere watch
```

### Option 2: Set JAVA_HOME manually

If your default Java version is not 21+, set JAVA_HOME before running:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
ampere-cli/build/install/ampere-cli-jvm/bin/ampere-cli watch
```

Or inline:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ampere-cli/build/install/ampere-cli-jvm/bin/ampere-cli watch
```

## Basic Usage

### Watch all events
```bash
./ampere-cli/ampere watch
```

This will display all events from the event bus in real-time.

### Filter by event type
```bash
# Watch only TaskCreated events
./ampere-cli/ampere watch --filter TaskCreated

# Watch multiple event types
./ampere-cli/ampere watch --filter TaskCreated --filter QuestionRaised
```

### Filter by agent
```bash
# Watch events from a specific agent
./ampere-cli/ampere watch --agent agent-pm

# Watch events from multiple agents
./ampere-cli/ampere watch --agent agent-pm --agent agent-dev
```

### Combine filters
```bash
# Watch TaskCreated events from agent-pm
./ampere-cli/ampere watch \
  --filter TaskCreated \
  --agent agent-pm
```

### Short option names
```bash
# Use -f for --filter and -a for --agent
./ampere-cli/ampere watch -f TaskCreated -a agent-pm
```

## Available Event Types

The following event types can be used with `--filter`:

### Base Events
- `TaskCreated`
- `QuestionRaised`
- `CodeSubmitted`

### Meeting Events
- `MeetingScheduled`
- `MeetingStarted`
- `AgendaItemStarted`
- `AgendaItemCompleted`
- `MeetingCompleted`
- `MeetingCanceled`

### Ticket Events
- `TicketCreated`
- `TicketStatusChanged`
- `TicketAssigned`
- `TicketBlocked`
- `TicketCompleted`
- `TicketMeetingScheduled`

### Message Events
- `ThreadCreated`
- `MessagePosted`
- `ThreadStatusChanged`
- `EscalationRequested`

### Notification Events
- `NotificationToAgent`
- `NotificationToHuman`

Event type names are **case-insensitive** (e.g., `taskcreated`, `TaskCreated`, `TASKCREATED` all work).

## Output Format

Events are displayed in the following format:
```
[timestamp] [icon] [event type]  [summary]
```

For example:
```
14:32:18  📋  TaskCreated              Task #123: Implement authentication (assigned to: agent-auth) [HIGH] from agent-pm
14:32:25  ❓  QuestionRaised           "How should we handle this error?" - Context: During database migration [MEDIUM] from agent-dev
14:33:01  💻  CodeSubmitted            src/main/Auth.kt - Add OAuth support (review required) for agent-reviewer [LOW] from agent-dev
```

### Color Coding

#### Event Types
- **Green** (📋🎫): Tasks and tickets (actionable items)
- **Magenta** (❓📅): Questions and meetings (need attention)
- **Cyan** (💻): Code submissions (technical)
- **Blue** (💬): Messages (communication)
- **White** (🔔): Notifications (informational)

#### Urgency Levels
- **Red** [HIGH]: Needs immediate attention
- **Yellow** [MEDIUM]: Should be addressed soon
- **Gray** [LOW]: Can wait

#### Other
- **Gray**: Timestamps and sources

## Integration Testing

To test the watch command with actual events, you'll need to:

1. **Start the watch command**:
   ```bash
   ./ampere-cli/ampere watch
   ```

2. **Publish events from another process** (using the event publishing API):
   ```kotlin
   import link.socket.ampere.agents.events.bus.EventSerialBus
   import link.socket.ampere.agents.events.Event
   import kotlinx.coroutines.runBlocking

   runBlocking {
       val eventBus = EventSerialBus(scope)
       eventBus.publish(Event.TaskCreated(...))
   }
   ```

The events should appear in the watch command output immediately.

## Troubleshooting

### No events appearing
- Make sure events are being published to the EventBus
- Check that your filters match the events being published
- Verify the event types are spelled correctly (use `--help` for examples)

### Invalid event type warning
If you see a warning about invalid event types, check the spelling and refer to the list of available event types above.

## Help

For full command help:
```bash
./ampere-cli/ampere watch --help
```
