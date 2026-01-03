# Jazz Test Demo - Integration Testing Suite

This directory contains scripts and documentation for validating the Jazz Test demo scenario, which tests fixes for issues #182 (input responsiveness) and #183 (output visibility).

## Quick Start

### 1. Pre-flight Check
```bash
./.ampere/demo/validate-demo-setup.sh
```

This validates:
- ✓ Build is complete
- ✓ Dispatcher fixes applied (#182)
- ✓ Output visibility fixes applied (#183)
- ✓ Terminal environment suitable
- ✓ API keys configured
- ✓ Database state

### 2. Run Demo
```bash
./ampere-cli/ampere test jazz
```

Follow the validation checklist in `jazz-test-scenario.md` during execution.

### 3. Post-Demo Validation
```bash
./.ampere/demo/validate-demo-results.sh
```

This checks:
- ✓ Fibonacci.kt file created
- ✓ Function implementation correct
- ✓ Database updated with ticket/knowledge
- ✓ No unexpected files created

## Files

### Documentation
- **`jazz-test-scenario.md`** - Complete demo script with validation checklist
  - Detailed step-by-step instructions
  - Input responsiveness tests
  - Output visibility verification
  - Recording setup guide

### Validation Scripts
- **`validate-demo-setup.sh`** - Pre-flight validation
  - Checks build status
  - Verifies fixes are applied
  - Validates environment
  - Exit code 0 = ready, 1 = errors

- **`validate-demo-results.sh`** - Post-demo validation
  - Checks generated files
  - Validates database state
  - Verifies file contents
  - Reports issues

## Critical Validation Points

### Input Responsiveness (#182)
During PERCEIVE and EXECUTE phases (10-30s LLM calls):
- [ ] Press 'h' → Help appears < 100ms ✅ PASS / ❌ FAIL
- [ ] Press '1'/'2'/'3' → Instant pane switching ✅ PASS / ❌ FAIL
- [ ] Type ':status' → No keyboard lag ✅ PASS / ❌ FAIL
- [ ] Press backspace → Deletes chars, not spaces ✅ PASS / ❌ FAIL

### Output Visibility (#183)
Each cognitive phase should show:
- [ ] PERCEIVE: Ideas generated (3+ items)
- [ ] PLAN: Task count and complexity
- [ ] EXECUTE: Files written with names and sizes
- [ ] LEARN: Knowledge stored with summary

## Expected Timeline

| Phase | Duration | User Action |
|-------|----------|-------------|
| Launch | 0:00-0:05 | Start demo |
| Initialize | 0:05-0:10 | Watch ticket creation |
| PERCEIVE | 0:10-0:30 | **Test input responsiveness** |
| PLAN | 0:30-0:45 | Observe plan output |
| EXECUTE | 0:45-1:30 | **Test input again, watch file creation** |
| LEARN | 1:30-1:45 | Observe knowledge extraction |
| Complete | 1:45-2:00 | Verify success, exit |

Total: ~2 minutes

## Recording Setup

For launch demo recording:

```bash
# Prepare environment
rm ~/.ampere/ampere.db
rm -rf ~/.ampere/jazz-test-output/*
./.ampere/demo/validate-demo-setup.sh

# Set terminal size
printf '\e[8;50;140t'  # 140 cols x 50 rows

# Start recording
asciinema rec jazz-demo-$(date +%Y%m%d-%H%M%S).cast --cols 140 --rows 50

# Run demo
./ampere-cli/ampere test jazz

# [Perform validation tests during execution]
# [Wait for completion]
# [Exit with 'q']

# Stop recording with Ctrl+D

# Validate results
./.ampere/demo/validate-demo-results.sh
```

## Troubleshooting

### Issue: Demo times out
**Check:** API keys in `local.properties`
```bash
grep "anthropic.api.key" local.properties
```

### Issue: Input still laggy
**Check:** Dispatcher fixes applied
```bash
grep -c "Dispatchers.IO" ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/CodeAgent.kt
# Should output: 7 or more
```

### Issue: No phase outputs visible
**Check:** Output tracking in place
```bash
grep "progressPane.setPerceiveResult" ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/goal/GoalHandler.kt
```

### Issue: Wrong file created or multiple files
**Review:** Agent prompt understanding
- Check ticket description in JazzTestRunner.kt:153-167
- Should specify "ONLY ONE file named Fibonacci.kt"

## Success Criteria

✅ **Demo is ready for recording when:**
1. Pre-flight validation passes (0 errors)
2. Test run shows input < 100ms response during LLM calls
3. All phase outputs visible and formatted correctly
4. Post-demo validation passes
5. Clean exit with no errors

⚠️ **Demo needs work if:**
1. Input lag > 1 second during LLM calls (#182 not fixed)
2. Phase outputs missing or truncated (#183 not fixed)
3. Backspace inserts spaces (terminal sync issue)
4. Multiple files created (agent prompt issue)
5. Errors or crashes during execution

## Related Issues

- #181 - Demo Blockers Epic: Input Responsiveness and Output Visibility
- #182 - P0: Fix Input Thread Starvation During Cognitive Processing
- #183 - P0: Add Agent Output Visibility to Jazz Test Pane
- #184 - Integration Test: Demo Scenario with Responsive Input (this)

## Notes

- Each test run costs ~$0.10 (Claude Sonnet 4 API)
- Recommended: 3 successful runs before official recording
- Save recordings with timestamps for comparison
- Keep validation script output for debugging
