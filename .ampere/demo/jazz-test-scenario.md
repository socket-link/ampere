# Jazz Test Demo Scenario - Validation Guide

## Purpose
Validate fixes for input responsiveness (#182) and output visibility (#183) through a repeatable demo scenario.

## Prerequisites

### Environment Setup
- [ ] Fresh database state (delete `~/.ampere/ampere.db` if needed)
- [ ] Terminal size: minimum 120x40 (recommended 140x50)
- [ ] API keys configured in `local.properties`:
  ```properties
  anthropic.api.key=sk-ant-...
  ```
- [ ] Latest build installed:
  ```bash
  ./gradlew :ampere-cli:installJvmDist
  ```

### Pre-flight Check
```bash
# Verify installation
ls -la ./ampere-cli/build/install/ampere-cli-jvm/bin/ampere-cli-jvm

# Check terminal size
printf "Cols: $(tput cols) Rows: $(tput lines)\n"
```

## Demo Script

### Phase 1: Launch (0:00-0:05)
```bash
./ampere-cli/ampere test jazz
```

**Expected:**
- TUI renders within 1-2 seconds
- Three panes visible: EVENT STREAM, JAZZ TEST, AGENT MEMORY
- JAZZ TEST pane shows:
  ```
  ╭─ JAZZ TEST DEMO ─────────────────────────────────╮
  │ [INITIALIZING] Creating ticket...                │
  │                                                   │
  │ Ticket ID: ticket-xxx                            │
  │ Agent ID: ProductManagerAgent-xxx                │
  │ ...                                              │
  ```

**Validation Checklist:**
- [ ] No green rectangle artifacts
- [ ] No token count leaking below TUI border
- [ ] Status bar shows agent state
- [ ] All pane borders render correctly

### Phase 2: Automatic Ticket Creation (0:05-0:10)

**Expected:**
- Ticket created automatically
- Agent assigned to ticket
- Cognitive cycle begins

**Observe:**
```
[PERCEIVE] Analyzing task...
```

### Phase 3: Input Responsiveness Test - CRITICAL (0:10-0:30)

During the PERCEIVE phase (which makes a 10-30 second LLM call):

#### Test 1: Help Key
```
Action: Press 'h'
Expected: Help screen appears within 100ms
         (Should NOT wait for LLM call to complete)
```
- [ ] PASS: Help appears < 100ms
- [ ] FAIL: Delay > 1 second
- [ ] Press ESC to close help

#### Test 2: Pane Switching
```
Action: Press '1' then '2' then '3'
Expected: Pane focus switches instantly
```
- [ ] PASS: Each switch happens within 100ms
- [ ] FAIL: Delay between presses

#### Test 3: Command Mode Typing
```
Action: Press ':' then type 'status' then ENTER
Expected: Each character appears instantly
         No lag between keypresses
```
- [ ] PASS: Typing is fluid, no delays
- [ ] FAIL: Character delay > 200ms

#### Test 4: Backspace Functionality
```
Action: Press ':' then type 'test' then press BACKSPACE 4 times
Expected: Characters are deleted, NOT spaces inserted
```
- [ ] PASS: Backspace deletes characters correctly
- [ ] FAIL: Backspace inserts spaces or doesn't delete

### Phase 4: Output Visibility - PERCEIVE (0:30-0:45)

**Expected Output in JAZZ TEST pane:**
```
╭─ JAZZ TEST DEMO ───────────────────────────────────╮
│ [PERCEIVE] Analyzing task...                       │
│                                                     │
│ Ideas Generated:                                    │
│   • PM Agent Perception State                      │
│   • Backlog Summary                                │
│   • Total Tickets: 1                               │
│                                                     │
│ ✓ Generated 3 idea(s)                              │
```

**Validation:**
- [ ] Phase label shows clearly
- [ ] Ideas are listed and readable
- [ ] Checkmark shows completion
- [ ] No text overflow or truncation

### Phase 5: Output Visibility - PLAN (0:45-1:00)

**Expected Output:**
```
│ [PLAN] Creating plan...                            │
│                                                     │
│ Plan Created:                                       │
│   • Tasks: 3                                        │
│   • Complexity: 5                                   │
│                                                     │
│ ✓ Plan created with 3 step(s)                      │
```

**Validation:**
- [ ] Plan details visible
- [ ] Task count shown
- [ ] Complexity rating displayed

### Phase 6: Output Visibility - EXECUTE (1:00-1:30)

**Expected Output:**
```
│ [EXECUTE] Calling LLM...                           │
│                                                     │
│ Files Written:                                      │
│   📝 Fibonacci.kt (423 chars)                      │
│                                                     │
│ ✓ Execution completed                              │
```

**Validation:**
- [ ] File creation shown in real-time
- [ ] Filename visible
- [ ] Character count shown
- [ ] Emoji renders correctly (📝)

**Critical Test:** During this 10-30 second LLM call:
- [ ] Press 'h' → Help still appears within 100ms
- [ ] Type ':status' → No keyboard lag

### Phase 7: Output Visibility - LEARN (1:30-1:45)

**Expected Output:**
```
│ [LEARN] Extracting knowledge...                    │
│                                                     │
│ Knowledge Stored:                                   │
│   Decomposed 'Implement Fibonacci' into 3 tasks.   │
│   Complexity: 5                                     │
│                                                     │
│ ✓ Knowledge extraction complete                    │
```

**Validation:**
- [ ] Knowledge summary visible
- [ ] Learning details shown
- [ ] No truncation of multi-line text

### Phase 8: Completion (1:45-2:00)

**Expected Output:**
```
│ [COMPLETED]                                         │
│                                                     │
│ ✅ SUCCESS! Agent completed the task in 87 seconds │
│                                                     │
│ 📄 Generated file: ~/.ampere/jazz-test-output/...  │
```

**Validation:**
- [ ] Success message displayed
- [ ] Completion time shown
- [ ] File path visible

### Phase 9: Exit (2:00-2:05)

```
Action: Press 'q'
Expected: Clean exit within 1 second
```

**Validation:**
- [ ] Exits cleanly
- [ ] No error messages
- [ ] Terminal restored to original state
- [ ] No lingering processes

## Overall Success Criteria

### Input Responsiveness (P0 - #182)
- [ ] All keypresses respond < 100ms during LLM calls
- [ ] Backspace deletes characters (not inserts spaces)
- [ ] No input lag during any phase
- [ ] Help, pane switching, command mode all responsive

### Output Visibility (P0 - #183)
- [ ] Phase labels clear and visible
- [ ] Per-phase outputs shown (ideas, plan, files, knowledge)
- [ ] No text overflow or truncation
- [ ] Emojis render correctly
- [ ] Progress indicators work

### UI Quality
- [ ] No green rectangle artifacts
- [ ] No token count leaking
- [ ] Borders render correctly
- [ ] Status bar accurate
- [ ] Clean startup and shutdown

## Troubleshooting

### Issue: Input lag still present
**Diagnosis:**
```bash
# Check if dispatcher fixes were applied
grep -n "Dispatchers.IO" ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/CodeAgent.kt
```
**Expected:** Should show ~7 matches

### Issue: No output during phases
**Diagnosis:**
```bash
# Check if output visibility was added
grep -n "progressPane.setPerceiveResult" ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/goal/GoalHandler.kt
```
**Expected:** Should find the method call

### Issue: Agent times out
**Possible causes:**
- API key not configured
- Network connectivity issues
- API rate limits reached

**Debug:**
```bash
# Check logs in EVENT STREAM pane
# Look for API errors or timeout messages
```

### Issue: Backspace still inserts spaces
**Diagnosis:** Terminal input handling conflict
**Workaround:** This may require deeper terminal synchronization fixes (P1)

## Recording Setup (for launch demo)

### Pre-recording Checklist
- [ ] Clean database: `rm ~/.ampere/ampere.db`
- [ ] Clear output directory: `rm -rf ~/.ampere/jazz-test-output/*`
- [ ] Terminal at 140x50: `printf "\\e[8;50;140t"`
- [ ] Font size readable at 1080p
- [ ] Rehearse all keystrokes

### Recording Commands
```bash
# Start recording
asciinema rec jazz-demo.cast --cols 140 --rows 50

# Run demo (follow Phase 1-9 above)
./ampere-cli/ampere test jazz

# When complete, exit and stop recording
# Press Ctrl+D
```

### Backup Plan
If agent behaves unexpectedly:
1. Have a pre-recorded fallback
2. Prepare manual code generation as backup
3. Document known issues upfront

## Post-Demo Validation

### Verify Generated Code
```bash
# Check that Fibonacci.kt was created
cat ~/.ampere/jazz-test-output/Fibonacci.kt

# Expected: Contains fibonacci function
grep -q "fun fibonacci" ~/.ampere/jazz-test-output/Fibonacci.kt && echo "✓ Function found" || echo "✗ Missing"
```

### Verify Database State
```bash
# Check that knowledge was stored
sqlite3 ~/.ampere/ampere.db "SELECT COUNT(*) FROM knowledge;"
```

## Notes
- Test time: ~2 minutes per run
- API cost: ~$0.10 per run (Claude Sonnet 4)
- Recommended: 3 successful runs before recording
- Critical phases for responsiveness: PERCEIVE, EXECUTE
