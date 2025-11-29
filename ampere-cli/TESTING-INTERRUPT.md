# Testing Guide: Graceful Ctrl+C Interruption (Ticket #66)

This document describes how to test the graceful interruption feature for the AMPERE CLI REPL.

## Prerequisites

- Working Gradle build environment with network access
- Java 21 or later
- Unix-like terminal that supports SIGINT signals

## Building the CLI

```bash
./gradlew :ampere-cli:installJvmDist
```

This will create the executable at `ampere-cli/build/install/jvm/bin/ampere`

## Test Procedure

### Test 1: Basic Interruption

**Objective:** Verify that Ctrl+C interrupts a running command without exiting the REPL

**Steps:**
1. Launch the REPL:
   ```bash
   ./ampere-cli/ampere
   ```

2. Verify the welcome banner displays:
   ```
   ╔═══════════════════════════════════════════════════════╗
   ║  AMPERE Interactive Shell v0.1.0                      ║
   ║  Autonomous Multi-Process Execution & Relay Env       ║
   ║                                                       ║
   ║  Type 'help' for commands, 'exit' to quit            ║
   ║  Press Ctrl+C to interrupt running observations       ║
   ╚═══════════════════════════════════════════════════════╝
   ```

3. Run the test command:
   ```
   ampere> test-interrupt
   ```

4. Verify output shows:
   ```
   Running for 30 seconds... Press Ctrl+C to interrupt
   ```

5. Within a few seconds, press `Ctrl+C`

**Expected Results:**
- The message `[Command interrupted]` should appear
- The REPL prompt `ampere>` should return
- The session should NOT exit
- No error messages or stack traces should appear

### Test 2: Multiple Interruptions

**Objective:** Verify that interruption works multiple times in succession

**Steps:**
1. From the same session as Test 1, run `test-interrupt` again
2. Press `Ctrl+C` within a few seconds
3. Repeat this process 3-4 times

**Expected Results:**
- Each interruption should behave identically
- The session should remain stable
- No memory leaks or resource exhaustion
- Each command should start fresh

### Test 3: Command Completion Without Interruption

**Objective:** Verify that commands can still complete normally

**Steps:**
1. Run any simple command like `help`
2. Verify it completes normally and returns to prompt

**Expected Results:**
- Help text displays correctly
- Prompt returns without any interruption messages
- Normal command flow is unaffected by the interruption infrastructure

### Test 4: Session Lifecycle

**Objective:** Verify clean startup and shutdown

**Steps:**
1. Launch the REPL
2. Run `test-interrupt`
3. Press `Ctrl+C` to interrupt
4. Run `help` to verify session is still responsive
5. Type `exit` to quit

**Expected Results:**
- Welcome banner displays on startup
- Interruption works as expected
- Help command executes normally
- Exit command displays: `Goodbye! Shutting down environment...`
- Process terminates cleanly with exit code 0

### Test 5: Rapid Interruptions

**Objective:** Test edge case of pressing Ctrl+C multiple times rapidly

**Steps:**
1. Run `test-interrupt`
2. Press `Ctrl+C` multiple times in rapid succession (3-5 times)

**Expected Results:**
- First `Ctrl+C` interrupts the command
- Additional `Ctrl+C` presses are handled gracefully
- Session remains stable
- No crashes or unexpected behavior
- Returns to prompt normally

### Test 6: Help Command Integration

**Objective:** Verify that test-interrupt appears in help text

**Steps:**
1. Run `help` command

**Expected Results:**
- Help output includes:
  ```
  test-interrupt      Test command for verifying Ctrl+C handling
  ```

## Known Limitations

Due to the current build environment constraints (network connectivity issues), automated testing could not be performed. This document provides a comprehensive manual testing procedure for when the build environment is available.

## Implementation Details

The interruption mechanism works by:

1. **Signal Handler**: Installed in `ReplSession.init()` using `sun.misc.Signal`
   - Catches SIGINT (Ctrl+C) signals
   - Delegates to `CommandExecutor.interrupt()`

2. **CommandExecutor**: Manages async command execution
   - Runs commands in cancellable coroutines
   - Maintains reference to current job
   - `interrupt()` method cancels the current job
   - Returns `CommandResult.INTERRUPTED` on cancellation

3. **Coroutine Hierarchy**:
   - Parent scope: REPL session (uses `SupervisorJob`)
   - Child scope: Individual commands
   - Cancelling child doesn't affect parent

4. **Test Command**: `test-interrupt`
   - Simulates long-running operation (30 second delay)
   - Provides clear feedback about interruption points
   - Demonstrates the interruption mechanism

## Files Modified

- `ampere-cli/src/jvmMain/kotlin/link/socket/ampere/repl/CommandExecutor.kt` (new)
- `ampere-cli/src/jvmMain/kotlin/link/socket/ampere/repl/CommandAdapter.kt` (new)
- `ampere-cli/src/jvmMain/kotlin/link/socket/ampere/repl/ReplSession.kt` (modified)

## Next Steps

Once this feature is validated through manual testing, the next ticket will integrate existing observation commands (watch, status, thread, outcomes) to run within the REPL using the `CommandExecutor` and `CommandAdapter` infrastructure.

This will enable commands like:

```
ampere> watch --filter TaskCreated
[Streaming events... Press Ctrl+C to stop]
14:32:18  📋  TaskCreated  Task #123: Implement auth
^C
[Watch stopped]

ampere> status
[Shows dashboard]

ampere> exit
```

## Related Tickets

- **Epic**: #64 - Add Interactive REPL Mode to AMPERE CLI
- **Depends On**: #65 - Implement REPL infrastructure and session management
- **Blocks**: TBD - Integrate observation commands into REPL
