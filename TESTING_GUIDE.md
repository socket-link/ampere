# Testing Guide - PROPEL Cognitive Loop

This guide shows you how to test and validate the PROPEL cognitive cycle implementation.

---

## Quick Start

### Option 1: Run the Demo (Recommended)

The fastest way to see the cognitive cycle in action:

```bash
# 1. Configure your LLM credentials (one of the following):
echo "ANTHROPIC_API_KEY=sk-ant-..." >> local.properties
# OR
echo "OPENAI_API_KEY=sk-..." >> local.properties

# 2. Compile to verify the code (optional)
./gradlew :ampere-core:compileTestKotlinJvm

# 3. Run the demo (requires valid LLM API credentials)
./gradlew :ampere-core:jvmTest --tests CognitiveCycleDemo
```

**Note**: The demo will compile successfully but requires valid LLM API credentials to run. Without credentials, you'll get a runtime error when it tries to call the LLM.

**What it does**: Runs a complete cognitive cycle for the task "Create a User data class" and shows detailed output for each phase.

**Expected output**:
```
================================================================================
PROPEL COGNITIVE CYCLE DEMONSTRATION
================================================================================

✓ Agent initialized: CodeWriterAgent-xxxxx

Task: Create a simple User data class with fields: name (String), email (String), and age (Int)

--------------------------------------------------------------------------------

PHASE 1: PERCEIVE
Analyzing current state and generating insights...

Perception ID: xxxxx
Ideas Generated: 1

  Idea: Perception analysis for Create a simple User data class...
  Description:
    Current task requires code generation → High confidence that write_code_file tool is appropriate
    No past failures detected → Proceed with standard approach
    ...

--------------------------------------------------------------------------------

PHASE 2: PLAN
Generating concrete execution plan...

Plan ID: xxxxx
Estimated Complexity: 2
Steps: 2

  Step 1:
    Type: Code Change
    Description: Determine package and file path for User data class
    Status: Pending

  Step 2:
    Type: Code Change
    Description: Generate complete User.kt file with data class definition
    Status: Pending

--------------------------------------------------------------------------------

PHASE 3: EXECUTE
Executing plan through executor abstraction...

  [SIMULATED] Writing file: src/main/kotlin/User.kt
  [SIMULATED] Content length: 243 chars

Outcome ID: xxxxx
Outcome Type: Success
Status: ✓ SUCCESS
Files Changed: 1
  - src/main/kotlin/User.kt
Duration: 150ms

--------------------------------------------------------------------------------

PHASE 4: LEARN
Extracting knowledge and generating learnings...

Knowledge Type: FromOutcome
Approach: Code change: Generate User data class (2 steps)
Timestamp: 2025-12-26T...

Learnings:
  ✓ Code changes succeeded
  Files modified: 1
    - src/main/kotlin/User.kt
  Validation: No validation performed
  Duration: 150ms

  This approach was successful for this type of code change task.

Learning Idea: Outcome evaluation: 1 execution analyzed
Description:
  Learnings from 1 execution outcomes (1 successful, 0 failed):

  1. Simple data class generation with explicit field types succeeds consistently

     Reasoning: The LLM-generated Kotlin code matched requirements precisely...
     Actionable Advice: For similar data class generation tasks, use the same approach...
     Confidence: high
     Evidence Count: 1

--------------------------------------------------------------------------------

COGNITIVE CYCLE SUMMARY

✓ Perception: Generated 1 insight(s)
✓ Planning: Created 2-step plan (complexity: 2)
✓ Execution: Succeeded
✓ Learning: Extracted knowledge and generated Outcome evaluation: 1 execution analyzed

Agent Memory State:
  Ideas: 2
  Perceptions: 1
  Plans: 1
  Tasks: 2
  Outcomes: 2
  Knowledge Entries: 1

================================================================================
DEMONSTRATION COMPLETE

The PROPEL cognitive cycle executed successfully:
  Vague Requirement → Perception → Plan → Execution → Learning

This demonstrates autonomous agency: the ability to transform
high-level intent into concrete action while learning from experience.
================================================================================
```

---

### Option 2: Run Integration Tests

Run the comprehensive test suite:

```bash
# 1. Configure LLM credentials (see above)

# 2. Remove @Ignore annotations from tests
# Edit: ampere-core/src/commonTest/kotlin/link/socket/ampere/agents/implementations/CodeWriterAgentIntegrationTest.kt
# Remove @Ignore from the tests you want to run

# 3. Run specific test
./gradlew :ampere-core:jvmTest --tests CodeWriterAgentIntegrationTest."test complete cognitive loop for simple task"

# OR run all integration tests
./gradlew :ampere-core:jvmTest --tests CodeWriterAgentIntegrationTest
```

**Available tests**:
1. `test complete cognitive loop for simple task` - Full PROPEL cycle
2. `test cognitive state transitions` - Verify state updates correctly
3. `test learnings persist across tasks` - Knowledge persistence
4. `test failure recovery` - Graceful failure handling
5. `test multiple tasks processed sequentially` - Multiple cycles
6. `test executor abstraction is used correctly` - Architecture validation
7. `test the Jazz Test - vague requirement to working code` - Ultimate autonomy test
8. `test runtime loop integration` - Continuous operation

---

## Configuration

### LLM Provider Setup

**Option A: OpenAI**
```properties
# local.properties
OPENAI_API_KEY=sk-...
```

**Option B: Anthropic (Claude)**
```properties
# local.properties
ANTHROPIC_API_KEY=sk-ant-...
```

**Option C: Google (Gemini)**
```properties
# local.properties
GOOGLE_API_KEY=...
```

### Adjusting the Demo

Edit `CognitiveCycleDemo.kt` to:

**1. Change the task:**
```kotlin
val task = Task.CodeChange(
    id = "demo-task-001",
    status = TaskStatus.Pending,
    description = "YOUR TASK HERE"
)
```

**2. Use real file writing:**
```kotlin
// Change:
val mockWriteCodeFile = createDemoWriteCodeFileTool()

// To:
val mockWriteCodeFile = createRealWriteCodeFileTool()
```

This will write actual files to `/tmp/ampere-demo-<timestamp>/`

**3. Test with real AgentMemoryService:**
```kotlin
// Add memory service for persistent knowledge storage
val memoryService = AgentMemoryService(
    agentId = "demo-agent",
    knowledgeRepository = ..., // Configure your repository
    eventBus = EventSerialBus()
)

val agent = CodeWriterAgent(
    // ... other parameters ...
    memoryServiceFactory = { memoryService }
)
```

---

## What Each Test Validates

### 1. Complete Cognitive Loop ✅
**Validates**: The full PROPEL cycle works end-to-end

**What it checks**:
- Perception generates non-empty insights
- Planning creates actionable steps
- Execution produces outcomes
- Evaluation generates learnings

### 2. Cognitive State Transitions ✅
**Validates**: AgentState updates correctly through the cycle

**What it checks**:
- Current memory updates at each cognitive phase
- Past memory accumulates without corruption
- State transitions are clean and predictable

### 3. Learnings Persist Across Tasks ✅
**Validates**: Knowledge extraction and retrieval works

**What it checks**:
- Knowledge extracted from Task 1
- Knowledge stored in agent state
- Knowledge retrievable for Task 2
- Learning loop actually closes

### 4. Failure Recovery ✅
**Validates**: Agent handles failures gracefully

**What it checks**:
- Failure outcomes recorded correctly
- Knowledge extracted from failures (not just successes)
- Agent continues operating after failure
- Failure learnings inform future behavior

### 5. Multiple Tasks Sequentially ✅
**Validates**: Agent can handle multiple cycles without corruption

**What it checks**:
- Each task gets full cognitive cycle
- Memory accumulates correctly across tasks
- No state corruption between tasks
- Knowledge compounds over time

### 6. Executor Abstraction ✅
**Validates**: Architectural pattern is respected

**What it checks**:
- Agent invokes tools through executors (not directly)
- Executor receives execute() calls
- Flow<ExecutionStatus> pattern works
- Clean separation of concerns maintained

### 7. The Jazz Test ✅
**Validates**: Ultimate autonomy - vague requirement → working code

**What it checks**:
- Natural language understanding
- Autonomous plan generation from intent
- Code generation without explicit instructions
- Complete autonomous transformation

### 8. Runtime Loop Integration ✅
**Validates**: Continuous operation

**What it checks**:
- Initialize → Run → Pause → Shutdown lifecycle
- Continuous cognitive loop execution
- No memory leaks or corruption over time
- Agent operates independently

---

## Troubleshooting

### LLM Call Fails

**Error**: `No response from LLM` or `401 Unauthorized`

**Solution**:
1. Check your API key is correct in `local.properties`
2. Verify API key has credits/quota remaining
3. Check network connectivity

### Test Times Out

**Error**: Test exceeds timeout

**Solution**:
1. LLM calls can be slow - increase test timeout
2. Check if LLM provider is experiencing issues
3. Try a faster model (e.g., GPT-4o-mini instead of GPT-4)

### JSON Parsing Fails

**Error**: `Failed to parse LLM response`

**Solution**:
1. Check LLM prompt temperature (should be low for JSON, e.g., 0.3)
2. Verify prompt includes "Respond ONLY with the JSON object"
3. Check markdown cleanup logic (removes ```json blocks)

### Knowledge Not Persisting

**Error**: Knowledge from Task 1 not available for Task 2

**Solution**:
1. Verify AgentMemoryService is configured
2. Check knowledge is being stored: `agent.getCurrentState().addToPastKnowledge(...)`
3. Verify recall query matches storage context

---

## Expected Behavior

### Successful Run

When everything works correctly, you should see:

1. **Perception Phase**:
   - Insights generated with confidence levels
   - Context-aware observations
   - Actionable recommendations

2. **Planning Phase**:
   - 1-5 concrete steps
   - Appropriate complexity estimation
   - Tool selection (if applicable)

3. **Execution Phase**:
   - Files written successfully
   - ExecutionOutcome.Success returned
   - Validation results (if configured)

4. **Learning Phase**:
   - Knowledge extracted from outcome
   - Learnings include approach + results
   - Future recommendations generated

### What "Good" Output Looks Like

**Good Perception Insight**:
```
"Task requires data class generation → Use standard Kotlin syntax
 Available write_code_file tool is appropriate
 No similar past failures detected → Proceed confidently"
```

**Good Plan**:
```
Step 1: Determine package structure from requirements
Step 2: Generate complete User.kt with data class
Step 3: Validate Kotlin syntax (optional)
```

**Good Learning**:
```
Approach: Generated data class with explicit types
Learning: Simple data classes with primitive types succeed consistently.
          Future tasks: Use same approach for similar requirements.
Confidence: High (based on 1 successful example)
```

---

## Next Steps

After validating the cognitive cycle:

1. **Run with Real Tasks**: Try actual code generation tasks
2. **Enable Memory Persistence**: Configure AgentMemoryService with SQLite
3. **Test Multiple Agents**: Run CodeWriterAgent, ProductManagerAgent, QualityAssuranceAgent
4. **Observe with CLI**: Use `./ampere-cli/ampere watch` to observe events in real-time
5. **Production Deployment**: See `PROPEL_COGNITIVE_LOOP_REVIEW.md` for deployment recommendations

---

## Additional Resources

- **Implementation Review**: `PROPEL_COGNITIVE_LOOP_REVIEW.md`
- **Architecture Documentation**: `CLAUDE.md`
- **CLI Documentation**: `ampere-cli/README.md`
- **Source Code**:
  - CodeWriterAgent: `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/CodeWriterAgent.kt`
  - AutonomousAgent: `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/AutonomousAgent.kt`
  - Test Suite: `ampere-core/src/commonTest/kotlin/link/socket/ampere/agents/implementations/CodeWriterAgentIntegrationTest.kt`

---

**Questions or Issues?**

If you encounter any issues running the tests or demo:
1. Check the troubleshooting section above
2. Review the implementation in `CodeWriterAgent.kt`
3. Enable verbose logging: Set log level to DEBUG in agent configuration
4. Check the event stream: `./ampere-cli/ampere watch --verbose`
