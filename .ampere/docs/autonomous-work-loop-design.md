# Autonomous Work Loop Design

## Current Architecture Analysis

### ✅ What's Already Built

1. **CodeAgent capabilities**:
   - `queryAvailableIssues()` - Find unassigned issues with 'code' label
   - `queryAssignedIssues()` - Find issues assigned to agent
   - `updateIssueStatus()` - Update issue labels to track workflow
   - `executeTask()` - Execute code change tasks with plan generation
   - Git operations (branch, commit, push, PR creation)

2. **IssueTrackerProvider**:
   - `GitHubCliProvider` exists and uses `gh` CLI
   - Supports `queryIssues()`, `updateIssue()`, `createIssue()`
   - **CRITICAL BUG**: `updateIssue()` doesn't update labels (line 292-294)

3. **Event system**:
   - Events published for workflow stages
   - EventBus infrastructure for pub/sub

### ❌ What's Missing

1. **IssueTrackerProvider wiring**:
   ```kotlin
   // In AgentFactory.kt, CodeAgent is created WITHOUT issueTrackerProvider:
   CodeAgent(
       agentConfiguration = agentConfiguration,
       toolWriteCodeFile = toolWriteCodeFile,
       coroutineScope = scope,
       memoryServiceFactory = memoryServiceFactory,
       // ❌ issueTrackerProvider = null (missing!)
       // ❌ repository = null (missing!)
   )
   ```

2. **Repository detection**: No logic to detect current repo from git remote

3. **Autonomous work loop**: No mechanism to continuously poll and work on issues

4. **Label update support**: GitHubCliProvider skips label updates

---

## Design Questions

### 1. Polling Strategy

**Question**: How should the agent discover new work?

**Options**:
- **A. Polling interval** (check every N seconds)
  - Pros: Simple, predictable
  - Cons: Wastes API calls, slow to respond

- **B. Event-driven** (GitHub webhooks)
  - Pros: Instant notification, efficient
  - Cons: Requires webhook setup, more complex

- **C. Hybrid** (poll with backoff)
  - Pros: Balance of simplicity and efficiency
  - Cons: Still some wasted calls

**Recommendation**: **Option C - Hybrid polling with exponential backoff**
```kotlin
// Poll every 30 seconds when idle
// Back off to 5 minutes if no issues found
// Reset to 30s when work is found
```

### 2. Work Queue Management

**Question**: How do we prevent race conditions with multiple agents?

**Scenario**: Two CodeAgent instances running, both see the same unassigned issue.

**Options**:
- **A. Optimistic locking** (claim via label, retry on conflict)
  - Implementation: Agent adds "assigned" label, checks if successful
  - Pros: Simple, stateless
  - Cons: Race condition window

- **B. Distributed lock** (Redis/database)
  - Implementation: Acquire lock before claiming issue
  - Pros: Prevents races
  - Cons: Requires external service

- **C. Single-agent assumption** (no coordination)
  - Implementation: Assume only one CodeAgent running
  - Pros: Simplest
  - Cons: Breaks if multiple instances

**Recommendation**: **Option A - Optimistic locking with retry**
```kotlin
suspend fun claimIssue(issueNumber: Int): Result<Unit> {
    // 1. Read current labels
    // 2. Add "assigned" label via updateIssue
    // 3. Re-read to verify we got it (check for race)
    // 4. If another agent claimed it, move to next issue
}
```

### 3. State Persistence

**Question**: How do we track what the agent is currently working on?

**Why it matters**: If Ampere crashes/restarts, we need to know:
- Which issue was the agent working on?
- What step of the plan was executing?
- Should we resume or abandon?

**Options**:
- **A. In-memory only** (lose state on restart)
- **B. SQLDelight database** (persist work state)
- **C. GitHub issue labels** (use labels as state machine)

**Recommendation**: **Option C - Use GitHub labels as the source of truth**
```kotlin
// Issue labels reflect true state:
// - No "assigned" label → Available for claiming
// - "assigned" → Claimed but not started
// - "in-progress" → Agent actively working
// - "in-review" → PR created, awaiting review
// - "blocked" → Failed, needs human intervention

// On restart, agent can:
// 1. Query issues with "in-progress" label
// 2. Either resume OR mark as blocked for human review
```

### 4. Error Handling & Recovery

**Question**: What happens when work fails?

**Failure scenarios**:
1. **Code generation fails** (LLM error, syntax error)
2. **Git operation fails** (merge conflict, auth issue)
3. **Plan execution fails** (timeout, resource limit)
4. **Issue update fails** (API error, network issue)

**Recovery strategies**:
```kotlin
class WorkResult {
    sealed interface Status {
        object Success : Status
        data class Retry(val afterDelay: Duration) : Status
        data class Blocked(val reason: String, val requiresHuman: Boolean) : Status
    }
}

suspend fun workOnIssue(issue: ExistingIssue): WorkResult {
    return try {
        // 1. Update to IN_PROGRESS
        // 2. Create plan
        // 3. Execute steps
        // 4. Create PR
        // 5. Update to IN_REVIEW
        WorkResult(Status.Success)
    } catch (e: RetryableException) {
        // Transient failure (API timeout, rate limit)
        updateIssueStatus(issue.number, BLOCKED, "Retrying: ${e.message}")
        WorkResult(Status.Retry(afterDelay = 5.minutes))
    } catch (e: Exception) {
        // Unrecoverable failure
        updateIssueStatus(issue.number, BLOCKED, "Failed: ${e.message}")
        WorkResult(Status.Blocked(e.message, requiresHuman = true))
    }
}
```

### 5. Resource Limits

**Question**: Should we limit concurrent work or work duration?

**Considerations**:
- LLM API rate limits (RPM, TPM)
- Git operations can be slow (large repos)
- Don't want one issue to block others forever

**Recommendation**:
```kotlin
class WorkLoopConfig(
    val maxConcurrentIssues: Int = 1,        // Only work on one issue at a time
    val maxExecutionTimePerIssue: Duration = 30.minutes,
    val maxIssuesPerHour: Int = 10,          // Rate limit protection
    val pollingInterval: Duration = 30.seconds,
    val backoffInterval: Duration = 5.minutes,
)
```

### 6. Work Loop Lifecycle

**Question**: How do we structure the autonomous loop?

**Proposed structure**:
```kotlin
class AutonomousWorkLoop(
    private val agent: CodeAgent,
    private val config: WorkLoopConfig,
    private val scope: CoroutineScope,
) {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var job: Job? = null

    fun start() {
        job = scope.launch {
            _isRunning.value = true
            var consecutiveNoWork = 0

            while (isRunning.value) {
                try {
                    // 1. Discover available issues
                    val issues = agent.queryAvailableIssues()

                    if (issues.isEmpty()) {
                        consecutiveNoWork++
                        val delay = calculateBackoff(consecutiveNoWork)
                        delay(delay)
                        continue
                    }

                    consecutiveNoWork = 0

                    // 2. Claim first issue
                    val issue = issues.first()
                    val claimed = agent.claimIssue(issue.number)

                    if (claimed.isFailure) {
                        // Another agent claimed it, try next
                        continue
                    }

                    // 3. Work on the issue
                    val result = agent.workOnIssue(issue)

                    // 4. Handle result
                    when (result.status) {
                        is WorkResult.Status.Success -> {
                            // Continue to next issue
                        }
                        is WorkResult.Status.Retry -> {
                            delay(result.status.afterDelay)
                        }
                        is WorkResult.Status.Blocked -> {
                            // Skip to next issue, human will intervene
                        }
                    }

                    // Rate limit protection
                    delay(config.pollingInterval)

                } catch (e: CancellationException) {
                    throw e // Propagate cancellation
                } catch (e: Exception) {
                    // Log error, continue loop
                    delay(config.backoffInterval)
                }
            }

            _isRunning.value = false
        }
    }

    fun stop() {
        _isRunning.value = false
        job?.cancel()
    }

    private fun calculateBackoff(consecutiveNoWork: Int): Duration {
        // Exponential backoff: 30s, 1m, 2m, 5m (capped)
        val seconds = minOf(30 * (2.0.pow(consecutiveNoWork)).toLong(), 300)
        return seconds.seconds
    }
}
```

---

## Implementation Plan

### Phase 1: Fix Foundation (Critical Blockers)

1. **Fix GitHubCliProvider label updates**
   - File: `GitHubCliProvider.kt`
   - Add `gh api` call to update labels via REST API
   - Test with integration test

2. **Wire up IssueTrackerProvider**
   - File: `AgentFactory.kt`
   - Create GitHubCliProvider instance
   - Detect repository from git remote
   - Pass to CodeAgent constructor

3. **Add repository detection**
   - File: `AmpereContext.kt` or new `RepositoryDetector.kt`
   - Run `git remote get-url origin`
   - Parse owner/repo from URL
   - Cache result

### Phase 2: Implement Work Loop

4. **Create AutonomousWorkLoop class**
   - File: `ampere-core/.../agents/execution/AutonomousWorkLoop.kt`
   - Implement polling logic with backoff
   - Add lifecycle management (start/stop)

5. **Add claimIssue method to CodeAgent**
   - Optimistic locking via labels
   - Retry on race condition

6. **Implement workOnIssue workflow**
   - Update to IN_PROGRESS
   - Create and execute plan
   - Create PR
   - Update to IN_REVIEW
   - Return WorkResult

### Phase 3: Integration

7. **Add to AmpereContext**
   - Create AutonomousWorkLoop for CodeAgent
   - Expose `startAutonomousWork()` / `stopAutonomousWork()`

8. **Wire to CLI**
   - Implement WorkCommand logic
   - Add --auto-work flag to StartCommand

---

## Critical Issues to Resolve

### Issue 1: Label Updates Not Working

**File**: `ampere-core/src/jvmMain/kotlin/link/socket/ampere/integrations/issues/github/GitHubCliProvider.kt:292-294`

```kotlin
// Labels and assignees require separate API calls
// For now, we skip these in the update implementation
// Future: Use gh api to update labels and assignees
```

**Impact**: `updateIssueStatus()` calls will silently fail to update labels!

**Fix**: Implement label updates via `gh api`
```bash
# Add label
gh api repos/OWNER/REPO/issues/123/labels -f labels='["in-progress"]'

# Or use simpler command
gh issue edit 123 --add-label "in-progress" --remove-label "assigned"
```

### Issue 2: No Repository Configuration

**Impact**: CodeAgent can't query issues without knowing which repo to use

**Options**:
1. Require `--repo owner/repo` flag
2. Auto-detect from `git remote`
3. Read from config file (`.ampere/config.json`)

**Recommendation**: Auto-detect with fallback to flag
```kotlin
fun detectRepository(): String? {
    // Try git remote
    val remote = executeCommand("git", "remote", "get-url", "origin")
    if (remote != null) {
        return parseRepoFromUrl(remote) // "owner/repo"
    }

    // Fallback to config file
    return readConfig()?.repository
}
```

---

## Questions for User

Before implementing, I need clarity on:

1. **Multiple agent instances**: Should we support multiple CodeAgents running simultaneously, or assume single instance?

2. **Work resumption**: If Ampere crashes while working on an issue, should we:
   - Resume where we left off?
   - Mark as blocked for human review?
   - Abandon and move to next issue?

3. **Rate limiting**: What limits should we set?
   - Issues per hour?
   - Max execution time per issue?
   - Concurrent work limit?

4. **Repository scope**: Should one Ampere instance:
   - Work on multiple repos?
   - Only work on current repo (detected from git)?

5. **Issue filters**: Should we filter by:
   - Labels only ('code')?
   - Also check assignee?
   - Priority levels?

---

## Recommended Next Steps

1. **User reviews this design** and answers questions above
2. **I implement Phase 1** (fix foundation issues)
3. **User tests issue discovery** with `ampere work --dry-run`
4. **I implement Phase 2** (work loop)
5. **User tests autonomous mode** with `ampere work --continuous`
