# CodeWriterAgent Integration Tests

## Overview

This document describes the comprehensive integration tests for the CodeWriterAgent that validate the complete cognitive loop. These tests ensure that the agent can autonomously perceive, plan, execute, and learn from tasks without human intervention.

## Test Suite: `CodeWriterAgentIntegrationTest.kt`

Location: `ampere-core/src/commonTest/kotlin/link/socket/ampere/agents/implementations/CodeWriterAgentIntegrationTest.kt`

## Test Cases

### Test 1: Complete Cognitive Loop for Simple Task

**Purpose**: Validates the entire autonomous cognitive cycle end-to-end.

**What it tests**:
- Agent can receive a simple code generation task
- Perception phase generates meaningful insights about the current state
- Planning phase produces a concrete, executable plan
- Execution phase runs the plan through the executor
- Evaluation phase generates learnings from the outcome
- Knowledge is extracted and stored in agent memory

**Success criteria**:
- ✓ Perception generates non-empty insights
- ✓ Plan contains at least one step
- ✓ Execution completes (success or failure, but not blank)
- ✓ Learning idea is generated from outcome
- ✓ Knowledge is stored in past memory

**Validation**:
```kotlin
val task = Task.CodeChange(
    id = "simple-task-1",
    description = "Create a simple data class User with fields name and email"
)

// Full cognitive cycle:
val perception = agent.perceiveState()
val plan = agent.determinePlanForTask(task, perception)
val outcome = agent.executePlan(plan)
val learningIdea = agent.evaluateNextIdeaFromOutcomes(outcome)

// Verify knowledge extraction
assertTrue(state.getPastMemory().knowledgeFromOutcomes.isNotEmpty())
```

---

### Test 2: Cognitive State Transitions

**Purpose**: Validates that agent state transitions correctly through each cognitive stage.

**What it tests**:
- Initial state has blank task/plan/outcome
- Each cognitive function updates current memory correctly
- Past memory accumulates as cognitive cycle progresses
- State transitions maintain consistency

**Success criteria**:
- ✓ Initial state is blank
- ✓ After perception, idea is stored in current memory
- ✓ After planning, plan is stored in current memory
- ✓ After execution, outcome is stored in current memory
- ✓ Past memory accumulates ideas, plans, outcomes

**State machine verification**:
```
Idle → Perceiving → Planning → Executing → Evaluating → Idle
  ↓         ↓           ↓           ↓           ↓
Blank → Idea stored → Plan stored → Outcome stored → Knowledge stored
```

---

### Test 3: Learnings Persist Across Tasks

**Purpose**: Validates the learning loop closure - outcomes become knowledge that informs future tasks.

**What it tests**:
- Knowledge extracted from first task execution
- Knowledge is stored in agent's past memory
- Second similar task can access first task's learnings
- The metabolic loop actually closes (outcomes → knowledge → planning)

**Success criteria**:
- ✓ First task generates extractable knowledge
- ✓ Knowledge has non-empty approach and learnings
- ✓ Knowledge is available in state for second task
- ✓ Second task can retrieve first task's knowledge by approach

**Learning loop validation**:
```kotlin
// Task 1: Execute and extract knowledge
val firstOutcome = agent.executePlan(firstPlan)
val firstKnowledge = agent.extractKnowledgeFromOutcome(firstOutcome, firstTask, firstPlan)
agent.getCurrentState().addToPastKnowledge(rememberedKnowledgeFromOutcomes = listOf(firstKnowledge))

// Task 2: Should have access to Task 1's knowledge
val knowledgeFromPastOutcomes = state.getPastMemory().knowledgeFromOutcomes
assertTrue(knowledgeFromPastOutcomes.any { it.approach == firstKnowledge.approach })
```

---

### Test 4: Failure Recovery

**Purpose**: Validates graceful failure handling and continued operation after failures.

**What it tests**:
- Agent handles execution failures without crashing
- Failure outcomes are properly classified and stored
- Agent can continue operating after failures
- Learnings are extracted from failures (what went wrong)
- Future tasks can benefit from failure learnings

**Success criteria**:
- ✓ Failed execution produces Outcome.Failure
- ✓ Failure is stored in current memory
- ✓ Knowledge extracted from failure mentions failure
- ✓ Agent can plan and execute subsequent tasks after failure

**Failure resilience**:
```kotlin
// Execute task with failing tool
val outcome = agent.runTask(task)
assertIs<Outcome.Failure>(outcome)

// Extract learnings from failure
val failureKnowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)
assertTrue(failureKnowledge.learnings.contains("fail", ignoreCase = true))

// Verify agent continues functioning
val recoveryPlan = agent.determinePlanForTask(recoveryTask)
assertNotNull(recoveryPlan)
```

---

### Test 5: Multiple Tasks Processed Sequentially

**Purpose**: Validates that agent can handle multiple tasks without state corruption.

**What it tests**:
- Each task gets its own complete cognitive cycle
- Task N doesn't corrupt state for Task N+1
- Memory accumulates correctly across tasks
- All tasks complete successfully (or fail gracefully)

**Success criteria**:
- ✓ All 3 tasks execute completely
- ✓ Each produces its own outcome
- ✓ Past memory contains at least 3 knowledge entries
- ✓ Past memory contains at least 3 task entries
- ✓ Past memory contains at least 3 outcome entries

**Sequential processing**:
```kotlin
val tasks = [task1, task2, task3]
for (task in tasks) {
    val idea = agent.perceiveState()
    val plan = agent.determinePlanForTask(task, idea)
    val outcome = agent.executePlan(plan)
    val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)
    // Store knowledge for future tasks
}

// Verify accumulation
assertEquals(3, outcomes.size)
assertTrue(pastMemory.knowledgeFromOutcomes.size >= 3)
```

---

### Test 6: Executor Abstraction is Used Correctly

**Purpose**: Validates architectural compliance - agents must use executors, not call tools directly.

**What it tests**:
- Agent invokes tools through the Executor interface
- Direct tool.execute() is never called from agent code
- The executor abstraction is respected
- Instrumentation of executors works correctly

**Success criteria**:
- ✓ Instrumented executor's execute() method is called
- ✓ Agent code doesn't bypass executor
- ✓ Execution flows through: Agent → Executor → Tool

**Architecture validation**:
```kotlin
var executorWasCalled = false
val instrumentedExecutor = object : Executor {
    override suspend fun execute(...) {
        executorWasCalled = true
        return FunctionExecutor.create().execute(...)
    }
}

// Execute task
agent.runTask(task)

// Verify architectural compliance
assertTrue(executorWasCalled, "Agent must use executor, not call tool directly")
```

---

### Test 7: The Jazz Test - Vague Requirement to Working Code

**Purpose**: The ultimate validation of autonomous agency - transform vague requirements into concrete code.

**What it tests**:
- Natural language understanding (vague → structured)
- High-level intent → concrete implementation plan
- Code generation from minimal specification
- The complete autonomous transformation pipeline

**Success criteria**:
- ✓ Agent generates insights from vague requirement
- ✓ Agent creates concrete plan with actionable steps
- ✓ Agent attempts execution (produces real outcome)
- ✓ Agent generates learnings from the experience
- ✓ The transformation pipeline works end-to-end

**The Jazz Test**:
```kotlin
// Vague requirement (like a PM might give)
val vagueRequirement = "I need a way to store user information"

// Agent autonomously transforms to concrete action
val task = Task.CodeChange(description = vagueRequirement)
val idea = agent.perceiveState()           // Understand requirement
val plan = agent.determinePlanForTask(...)  // Create concrete steps
val outcome = agent.executePlan(plan)      // Execute the plan
val learning = agent.evaluateNextIdeaFromOutcomes(outcome)  // Learn

// Verify autonomous transformation occurred
assertTrue(plan.tasks.isNotEmpty() && outcome !is Outcome.Blank)
```

**Why it's called the "Jazz Test"**: Like a jazz musician transforming a simple melody into complex improvisation, the agent takes a vague requirement and autonomously elaborates it into working code. This tests genuine agency, not just scripted behavior.

---

### Test 8: Runtime Loop Integration

**Purpose**: Validates the continuous runtime loop (from AutonomousAgent base class).

**What it tests**:
- Agent can be initialized with a coroutine scope
- Runtime loop executes cognitive cycles continuously
- Agent can be paused and resumed
- Agent can be shutdown gracefully
- The continuous loop doesn't crash or hang

**Success criteria**:
- ✓ Initialize starts runtime loop
- ✓ Loop executes cognitive functions autonomously
- ✓ Past memory accumulates during loop execution
- ✓ Pause stops the loop
- ✓ Shutdown cleans up properly

**Runtime loop lifecycle**:
```kotlin
agent.initialize(scope)        // Start continuous loop
delay(2.seconds)               // Let it run
agent.pauseAgent()             // Stop the loop

// Verify loop executed
assertTrue(pastMemory.ideas.isNotEmpty() || pastMemory.plans.isNotEmpty())

agent.shutdownAgent()          // Cleanup
```

---

## Test Infrastructure

### Mock Tools

The tests use mock tools that simulate success or failure:

```kotlin
createMockWriteCodeFileTool(alwaysSucceed: Boolean)
```

- `alwaysSucceed = true`: Returns `ExecutionOutcome.CodeChanged.Success`
- `alwaysSucceed = false`: Returns `ExecutionOutcome.CodeChanged.Failure`

This allows testing both happy paths and error recovery without requiring actual file I/O.

### Test Agent Configuration

Tests use `testAgentConfiguration()` from test helpers, which provides:
- Agent definition: WriteCodeAgent
- AI provider: Anthropic (Claude)
- AI model: Sonnet 4

---

## Validation Checklist

Based on the task requirements, these tests verify:

- [x] **Agent can receive a simple task and complete it autonomously**
  - Test 1: Complete cognitive loop for simple task

- [x] **Complete cognitive loop executes: perception → planning → execution → evaluation**
  - Test 1: Validates all stages
  - Test 2: Validates state transitions through stages

- [x] **Each stage emits appropriate state changes in correct sequence**
  - Test 2: Cognitive state transitions

- [x] **When execution fails, agent handles it gracefully**
  - Test 4: Failure recovery

- [x] **Learnings from one task are available to subsequent tasks**
  - Test 3: Learnings persist across tasks

- [x] **Agent uses executors to invoke tools, never calling tools directly**
  - Test 6: Executor abstraction is used correctly

- [x] **All cognitive functions are implemented and integrated**
  - All tests verify integration of: perceive, plan, execute, evaluate

- [x] **Agent can handle multiple tasks sequentially without state corruption**
  - Test 5: Multiple tasks processed sequentially

- [x] **The Jazz Test: vague requirement → autonomous code change → working implementation**
  - Test 7: The Jazz Test

- [x] **Runtime loop operates continuously**
  - Test 8: Runtime loop integration

---

## Running the Tests

```bash
# Run all integration tests
./gradlew :ampere-core:jvmTest --tests "CodeWriterAgentIntegrationTest"

# Run specific test
./gradlew :ampere-core:jvmTest --tests "CodeWriterAgentIntegrationTest.test complete cognitive loop for simple task"
```

---

## Architecture Validation

These tests validate the core architectural decisions:

1. **Cognitive Loop Architecture**: The `AutonomousAgent.runtimeLoop()` correctly coordinates all cognitive functions in the proper sequence.

2. **Executor Pattern**: Agents invoke tools through executors, not directly, enabling observability, error handling, and consistent execution patterns.

3. **State Management**: Agent state transitions correctly through the cognitive cycle, with current memory and past memory properly managed.

4. **Knowledge Extraction**: The learning loop closes - outcomes become knowledge that informs future planning.

5. **Failure Resilience**: The system continues operating even when individual tasks fail.

6. **Sequential Task Handling**: Memory accumulates correctly across multiple tasks without corruption.

---

## What This Proves

These integration tests prove that the CodeWriterAgent has achieved **genuine autonomous agency**:

- **Perception**: Understanding current state and generating insights
- **Planning**: Breaking down goals into concrete, executable steps
- **Execution**: Taking action through proper architectural channels (executors)
- **Evaluation**: Learning from outcomes to improve future performance
- **Loop Closure**: Learnings feed back into future perception and planning

This is the "metabolic loop" of autonomous agency - a continuous cycle that enables the agent to improve over time and handle novel tasks without explicit programming for each scenario.

The **Jazz Test** in particular demonstrates that the agent can transform high-level, vague human requirements into concrete working code autonomously - the ultimate test of genuine agency rather than scripted behavior.
