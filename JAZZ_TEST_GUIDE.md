# The Jazz Test - End-to-End Autonomous Agent Demonstration

This guide will walk you through running the **Jazz Test**, a complete demonstration of an autonomous agent working through the PROPEL cognitive cycle.

## What is the Jazz Test?

The Jazz Test demonstrates:
1. ✅ AMPERE environment with event-driven architecture
2. ✅ CodeWriterAgent listening for ticket events
3. ✅ Autonomous cognitive cycle: **Perceive → Plan → Execute → Learn**
4. ✅ Real code generation (Fibonacci function in Kotlin)
5. ✅ Observable through the CLI dashboard

## Prerequisites

1. **LLM API Credentials**: You need Anthropic Claude API credentials in `local.properties`:
   ```properties
   anthropic.api.key=your-api-key-here
   ```

2. **Build the CLI**:
   ```bash
   ./gradlew :ampere-cli:installJvmDist
   ```

## Running the Jazz Test

### Option 1: Run the Jazz Test Program (Recommended)

The simplest way to run the Jazz Test:

```bash
# Make sure the CLI is built
./gradlew :ampere-cli:installJvmDist

# Run the Jazz Test
./ampere-cli/ampere jazz-test
```

This will:
- Start the AMPERE environment
- Create a CodeWriterAgent
- Create and assign a Fibonacci ticket
- The agent autonomously works through the cognitive cycle
- Generate `Fibonacci.kt` in `~/.ampere/jazz-test-output/`
- Show progress in the terminal

### Option 2: Observe with the Dashboard

For a more immersive experience, run the Jazz Test **and** observe it through the dashboard simultaneously:

**Terminal 1** - Start the dashboard:
```bash
./ampere-cli/ampere start
```

**Terminal 2** - Run the Jazz Test:
```bash
./ampere-cli/ampere jazz-test
```

In the dashboard, you'll see:
- **Dashboard mode (d)**: Agent status, events, system vitals
- **Event stream mode (e)**: Real-time event flow
- **Memory ops mode (m)**: Knowledge operations
- **Agent focus mode (1-9)**: Detailed agent view

### Option 3: Run the Original Demo Test

The original cognitive cycle demo (without events):

```bash
./gradlew :ampere-core:jvmTest --tests CognitiveCycleDemo
```

## What You Should See

When the Jazz Test runs successfully, you'll see:

```
═══════════════════════════════════════════════════════════════
THE JAZZ TEST - Autonomous Agent End-to-End Demonstration
═══════════════════════════════════════════════════════════════

📁 Output directory: /Users/you/.ampere/jazz-test-output

✅ AMPERE environment started
📡 Event bus active
💾 Database ready

🤖 CodeWriterAgent created
   Agent ID: CodeWriterAgent-abc-123

✅ Agent subscribed to events

────────────────────────────────────────────────────────────────
CREATING FIBONACCI TICKET
────────────────────────────────────────────────────────────────

✅ Ticket created successfully!
   Ticket ID: ticket-xyz-789
   Title: Implement Fibonacci function
   Assigned to: CodeWriterAgent-abc-123
   Thread ID: thread-456

────────────────────────────────────────────────────────────────
AGENT COGNITIVE CYCLE IN PROGRESS
────────────────────────────────────────────────────────────────

The agent will now autonomously:
  1. 🧠 PERCEIVE - Analyze the task and generate insights
  2. 📋 PLAN - Create a concrete execution plan
  3. ⚡ EXECUTE - Write the Kotlin code
  4. 📚 LEARN - Extract knowledge from the outcome

🔄 [COGNITIVE CYCLE] Starting...

   🧠 [PHASE 1: PERCEIVE] Analyzing current state...
      Generated 1 idea(s)

   📋 [PHASE 2: PLAN] Creating execution plan...
      Created plan with 1 step(s)
      Estimated complexity: SIMPLE

   ⚡ [PHASE 3: EXECUTE] Executing plan...
      📝 Wrote file: Fibonacci.kt (234 chars)
      ✅ Success! Changed 1 file(s)
         - Fibonacci.kt

   📚 [PHASE 4: LEARN] Extracting knowledge...
      Approach: Code change: Create a Kotlin function...
      Learnings:
         ✓ Code changes succeeded
         Files modified: 1
         - Fibonacci.kt

✅ [COGNITIVE CYCLE] Complete!

═══════════════════════════════════════════════════════════════
✅ SUCCESS! Agent completed the task in 12 seconds
═══════════════════════════════════════════════════════════════

📄 Generated file: /Users/you/.ampere/jazz-test-output/Fibonacci.kt

File contents:
────────────────────────────────────────────────────────────────
fun fibonacci(n: Int): Long {
    if (n <= 1) return n.toLong()

    var prev = 0L
    var current = 1L

    repeat(n - 1) {
        val next = prev + current
        prev = current
        current = next
    }

    return current
}
────────────────────────────────────────────────────────────────

✅ Code validation passed
   ✓ Contains fibonacci function
   ✓ Uses appropriate types
```

## Debugging

If the agent doesn't complete within 60 seconds:

1. **Check API credentials**: Ensure `anthropic.api.key` is set in `local.properties`
2. **Check the dashboard**: Run `./ampere-cli/ampere start` to see what's happening
3. **Check events**: Events should flow through the system:
   - `TicketCreated`
   - `TicketAssigned`
   - Tool execution events
   - Knowledge storage events

## Understanding the Cognitive Cycle

### Phase 1: PERCEIVE
The agent analyzes its current state and generates ideas. It looks at:
- Pending tasks
- Available tools
- Past knowledge and outcomes

### Phase 2: PLAN
The agent creates a concrete execution plan:
- Breaks down the task into steps
- Estimates complexity
- Identifies required tools

### Phase 3: EXECUTE
The agent executes the plan:
- Calls the write_code_file tool
- Generates Kotlin code
- Validates the outcome

### Phase 4: LEARN
The agent extracts knowledge:
- What approach was used
- Whether it succeeded or failed
- Learnings for future tasks

## Next Steps

After running the Jazz Test successfully:

1. **Try different tasks**: Modify the ticket description
2. **Observe events**: Watch the event stream in the dashboard
3. **Check the database**: Events are persisted in `~/.ampere/ampere.db`
4. **Add more agents**: Create multiple agents and see them collaborate

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   AMPERE Environment                    │
├─────────────────────────────────────────────────────────┤
│  EventBus ←→ TicketOrchestrator ←→ TicketRepository    │
│      ↕                                                   │
│  CodeWriterAgent                                         │
│      ├─ Perception (Analyze)                            │
│      ├─ Planning (Strategize)                           │
│      ├─ Execution (Act)                                 │
│      └─ Learning (Reflect)                              │
│                                                          │
│  Tools: write_code_file → File System                   │
│  Memory: KnowledgeRepository → SQLite                   │
└─────────────────────────────────────────────────────────┘
```

## Troubleshooting

### "No API key found"
Add your Anthropic API key to `local.properties`:
```properties
anthropic.api.key=sk-ant-...
```

### "Agent did not complete"
- Check if the LLM API is responding
- Increase the timeout in the code
- Check the dashboard for error events

### "File not created"
- Check the output directory permissions
- Look for error events in the dashboard
- Check the agent's tool execution logs

## Related Files

- **Jazz Test Runner**: `ampere-cli/src/jvmMain/kotlin/link/socket/ampere/demo/JazzTestRunner.kt`
- **Cognitive Cycle Demo**: `ampere-core/src/jvmTest/kotlin/link/socket/ampere/agents/demo/CognitiveCycleDemo.kt`
- **CodeWriterAgent**: `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/CodeWriterAgent.kt`

## Success Criteria

✅ The Jazz Test is successful when:
1. Agent perceives the ticket assignment
2. Agent generates a concrete plan
3. Agent writes working Kotlin code to a file
4. Agent stores knowledge about the approach
5. All events are emitted and observable in the dashboard

You've now demonstrated **autonomous agency** - the ability to transform vague requirements into concrete action while learning from experience!
