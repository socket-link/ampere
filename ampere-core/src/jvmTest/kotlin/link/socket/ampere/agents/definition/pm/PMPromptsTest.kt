package link.socket.ampere.agents.definition.pm

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for PM Agent prompt generation.
 *
 * These tests verify that prompts:
 * - Include all necessary context
 * - Provide clear guidelines
 * - Handle edge cases gracefully
 * - Request properly formatted JSON output
 */
class PMPromptsTest {

    @Test
    fun `goalDecompositionPrompt includes goal and repository`() {
        val prompt = PMPrompts.goalDecompositionPrompt(
            goal = "Implement user authentication",
            repository = "owner/repo",
            availableAgents = emptyList(),
        )

        assertContains(prompt, "Implement user authentication", message = "Prompt should include the goal")
        assertContains(prompt, "owner/repo", message = "Prompt should include repository")
        assertContains(prompt, "\"repository\":", message = "Prompt should show JSON schema")
    }

    @Test
    fun `goalDecompositionPrompt lists available agents`() {
        val agents = listOf(
            AgentCapability(
                agentId = "code-writer-1",
                capabilities = listOf("code-writing", "kotlin"),
                currentTaskCount = 2,
            ),
            AgentCapability(
                agentId = "qa-tester-1",
                capabilities = listOf("qa-testing", "validation"),
                currentTaskCount = 1,
            ),
        )

        val prompt = PMPrompts.goalDecompositionPrompt(
            goal = "Build payment system",
            repository = "owner/repo",
            availableAgents = agents,
        )

        assertContains(prompt, "code-writer-1", message = "Prompt should list agent IDs")
        assertContains(prompt, "code-writing", message = "Prompt should list capabilities")
        assertContains(prompt, "2 tasks", message = "Prompt should show current workload")
    }

    @Test
    fun `goalDecompositionPrompt includes existing issues when provided`() {
        val existingIssues = listOf(
            "Issue #100: Set up authentication database",
            "Issue #101: Create login API endpoint",
        )

        val prompt = PMPrompts.goalDecompositionPrompt(
            goal = "Complete authentication system",
            repository = "owner/repo",
            availableAgents = emptyList(),
            existingIssues = existingIssues,
        )

        assertContains(prompt, "Existing Issues", message = "Prompt should have existing issues section")
        assertContains(prompt, "Issue #100", message = "Prompt should list specific issues")
        assertContains(prompt, "Avoid Duplication", message = "Prompt should warn about duplication")
    }

    @Test
    fun `goalDecompositionPrompt handles many existing issues`() {
        val existingIssues = (1..15).map { "Issue #$it: Task $it" }

        val prompt = PMPrompts.goalDecompositionPrompt(
            goal = "Add new feature",
            repository = "owner/repo",
            availableAgents = emptyList(),
            existingIssues = existingIssues,
        )

        assertContains(prompt, "Issue #1", message = "Prompt should show first issues")
        assertContains(prompt, "Issue #10", message = "Prompt should show first 10 issues")
        assertContains(prompt, "and 5 more", message = "Prompt should indicate there are more")
    }

    @Test
    fun `goalDecompositionPrompt provides work breakdown guidelines`() {
        val prompt = PMPrompts.goalDecompositionPrompt(
            goal = "Test goal",
            repository = "owner/repo",
            availableAgents = emptyList(),
        )

        assertContains(prompt, "Epic Structure", message = "Should explain epic structure")
        assertContains(prompt, "Task Granularity", message = "Should explain task granularity")
        assertContains(prompt, "Dependency Identification", message = "Should explain dependencies")
        assertContains(prompt, "3-8 Task issues", message = "Should specify task count range")
        assertContains(prompt, "dependsOn", message = "Should explain dependency field")
    }

    @Test
    fun `goalDecompositionPrompt requests valid JSON output`() {
        val prompt = PMPrompts.goalDecompositionPrompt(
            goal = "Test goal",
            repository = "owner/repo",
            availableAgents = emptyList(),
        )

        assertContains(prompt, "valid JSON", message = "Should request JSON output")
        assertContains(prompt, "\"issues\":", message = "Should show JSON schema")
        assertContains(prompt, "\"localId\":", message = "Should show required fields")
        assertContains(prompt, "\"type\":", message = "Should show issue types")
        assertContains(prompt, "no markdown code blocks", message = "Should specify no markdown")
    }

    @Test
    fun `taskAssignmentPrompt includes task description`() {
        val agents = listOf(
            AgentCapability("agent-1", listOf("code-writing"), 0),
        )

        val prompt = PMPrompts.taskAssignmentPrompt(
            task = "Write authentication middleware",
            availableAgents = agents,
        )

        assertContains(prompt, "Write authentication middleware", message = "Prompt should include task")
        assertContains(prompt, "agent-1", message = "Prompt should list agents")
    }

    @Test
    fun `taskAssignmentPrompt handles no available agents`() {
        val prompt = PMPrompts.taskAssignmentPrompt(
            task = "Some task",
            availableAgents = emptyList(),
        )

        assertContains(prompt, "No agents available", message = "Should indicate no agents")
        assertContains(prompt, "\"assignedAgent\": null", message = "Should show null assignment")
    }

    @Test
    fun `taskAssignmentPrompt shows agent capabilities and workload`() {
        val agents = listOf(
            AgentCapability(
                agentId = "code-agent",
                capabilities = listOf("code-writing", "kotlin", "testing"),
                currentTaskCount = 3,
            ),
            AgentCapability(
                agentId = "doc-agent",
                capabilities = listOf("documentation", "markdown"),
                currentTaskCount = 7,
            ),
        )

        val prompt = PMPrompts.taskAssignmentPrompt(
            task = "Implement feature X",
            availableAgents = agents,
        )

        assertContains(prompt, "code-writing", message = "Should list capabilities")
        assertContains(prompt, "3 tasks", message = "Should show workload")
        assertContains(prompt, "At capacity", message = "Should indicate high workload")
        assertContains(prompt, "Available", message = "Should indicate availability")
    }

    @Test
    fun `taskAssignmentPrompt provides assignment criteria`() {
        val agents = listOf(AgentCapability("agent-1", listOf("code-writing"), 0))

        val prompt = PMPrompts.taskAssignmentPrompt(
            task = "Task",
            availableAgents = agents,
        )

        assertContains(prompt, "Capability Match", message = "Should explain capability matching")
        assertContains(prompt, "Current Workload", message = "Should explain workload consideration")
        assertContains(prompt, "Past Performance", message = "Should mention past performance")
    }

    @Test
    fun `taskAssignmentPrompt requests JSON with reasoning`() {
        val agents = listOf(AgentCapability("agent-1", listOf("code-writing"), 0))

        val prompt = PMPrompts.taskAssignmentPrompt(
            task = "Task",
            availableAgents = agents,
        )

        assertContains(prompt, "\"assignedAgent\":", message = "Should show JSON field")
        assertContains(prompt, "\"reasoning\":", message = "Should request reasoning")
        assertContains(prompt, "valid JSON", message = "Should request valid JSON")
    }

    @Test
    fun `progressAssessmentPrompt includes epic and task details`() {
        val tasks = listOf(
            TaskSummary("task-1", "Implement API", "completed"),
            TaskSummary("task-2", "Write tests", "in_progress"),
            TaskSummary("task-3", "Deploy", "pending", dependsOn = listOf("task-2")),
        )

        val prompt = PMPrompts.progressAssessmentPrompt(
            epicTitle = "Build payment system",
            tasks = tasks,
        )

        assertContains(prompt, "Build payment system", message = "Should include epic title")
        assertContains(prompt, "3 total", message = "Should show task count")
        assertContains(prompt, "task-1", message = "Should list task IDs")
        assertContains(prompt, "Implement API", message = "Should list task titles")
        assertContains(prompt, "completed", message = "Should show status")
        assertContains(prompt, "Depends on: task-2", message = "Should show dependencies")
    }

    @Test
    fun `progressAssessmentPrompt handles no tasks`() {
        val prompt = PMPrompts.progressAssessmentPrompt(
            epicTitle = "Empty epic",
            tasks = emptyList(),
        )

        assertContains(prompt, "No tasks defined", message = "Should indicate no tasks")
        assertContains(prompt, "\"progressPercentage\": 0", message = "Should suggest 0% progress")
        assertContains(prompt, "Epic needs task breakdown", message = "Should suggest defining tasks")
    }

    @Test
    fun `progressAssessmentPrompt shows status breakdown`() {
        val tasks = listOf(
            TaskSummary("task-1", "Task 1", "completed"),
            TaskSummary("task-2", "Task 2", "completed"),
            TaskSummary("task-3", "Task 3", "in_progress"),
            TaskSummary("task-4", "Task 4", "blocked"),
            TaskSummary("task-5", "Task 5", "pending"),
        )

        val prompt = PMPrompts.progressAssessmentPrompt(
            epicTitle = "Epic",
            tasks = tasks,
        )

        assertContains(prompt, "Completed: 2", message = "Should count completed tasks")
        assertContains(prompt, "In Progress: 1", message = "Should count in-progress tasks")
        assertContains(prompt, "Blocked: 1", message = "Should count blocked tasks")
        assertContains(prompt, "Pending: 1", message = "Should count pending tasks")
    }

    @Test
    fun `progressAssessmentPrompt includes recent events`() {
        val events = listOf(
            "TaskCompleted: task-1 completed by agent-code-1",
            "TaskBlocked: task-3 blocked on external dependency",
            "TaskAssigned: task-4 assigned to agent-qa-1",
        )

        val prompt = PMPrompts.progressAssessmentPrompt(
            epicTitle = "Epic",
            tasks = listOf(TaskSummary("task-1", "Task", "done")),
            recentEvents = events,
        )

        assertContains(prompt, "Recent Events", message = "Should have events section")
        assertContains(prompt, "TaskCompleted", message = "Should list events")
        assertContains(prompt, "last 3", message = "Should show event count")
    }

    @Test
    fun `progressAssessmentPrompt handles many events`() {
        val events = (1..15).map { "Event $it: Something happened" }

        val prompt = PMPrompts.progressAssessmentPrompt(
            epicTitle = "Epic",
            tasks = listOf(TaskSummary("task-1", "Task", "done")),
            recentEvents = events,
        )

        assertContains(prompt, "Event 1", message = "Should show first events")
        assertContains(prompt, "Event 10", message = "Should show first 10 events")
        assertContains(prompt, "and 5 more", message = "Should indicate there are more")
    }

    @Test
    fun `progressAssessmentPrompt provides assessment guidelines`() {
        val prompt = PMPrompts.progressAssessmentPrompt(
            epicTitle = "Epic",
            tasks = listOf(TaskSummary("task-1", "Task", "done")),
        )

        assertContains(prompt, "Progress Percentage", message = "Should explain progress calculation")
        assertContains(prompt, "Blocked Tasks", message = "Should explain blocked tasks")
        assertContains(prompt, "Risks", message = "Should explain risk identification")
        assertContains(prompt, "Recommended Actions", message = "Should explain recommended actions")
        assertContains(prompt, "Human Input Required", message = "Should explain escalation criteria")
    }

    @Test
    fun `progressAssessmentPrompt requests structured JSON output`() {
        val prompt = PMPrompts.progressAssessmentPrompt(
            epicTitle = "Epic",
            tasks = listOf(TaskSummary("task-1", "Task", "done")),
        )

        assertContains(prompt, "valid JSON", message = "Should request valid JSON")
        assertContains(prompt, "\"progressPercentage\":", message = "Should show progress field")
        assertContains(prompt, "\"blockedTasks\":", message = "Should show blocked tasks field")
        assertContains(prompt, "\"risks\":", message = "Should show risks field")
        assertContains(prompt, "\"recommendedActions\":", message = "Should show actions field")
        assertContains(prompt, "\"needsHumanInput\":", message = "Should show escalation field")
        assertContains(prompt, "\"humanInputReason\":", message = "Should show reason field")
    }

    @Test
    fun `all prompts avoid markdown code blocks in JSON schema examples`() {
        val goal = PMPrompts.goalDecompositionPrompt("Goal", "repo", emptyList())
        val assignment = PMPrompts.taskAssignmentPrompt("Task", listOf(AgentCapability("a", listOf("x"), 0)))
        val progress = PMPrompts.progressAssessmentPrompt("Epic", listOf(TaskSummary("t", "T", "done")))

        // Prompts should not ask for markdown code blocks around JSON
        assertFalse(goal.contains("```json") && goal.contains("Output Format"), message = "Goal prompt should not request markdown in output section")
        assertFalse(assignment.contains("```json") && assignment.contains("Output Format"), message = "Assignment prompt should not request markdown in output section")
        assertFalse(progress.contains("```json") && progress.contains("Output Format"), message = "Progress prompt should not request markdown in output section")

        // But they should show examples (these may use markdown for illustration)
        assertTrue(goal.contains("{"), message = "Goal prompt should show JSON structure")
        assertTrue(assignment.contains("{"), message = "Assignment prompt should show JSON structure")
        assertTrue(progress.contains("{"), message = "Progress prompt should show JSON structure")
    }

    @Test
    fun `prompts specify no markdown in output`() {
        val goal = PMPrompts.goalDecompositionPrompt("Goal", "repo", emptyList())
        val assignment = PMPrompts.taskAssignmentPrompt("Task", listOf(AgentCapability("a", listOf("x"), 0)))
        val progress = PMPrompts.progressAssessmentPrompt("Epic", listOf(TaskSummary("t", "T", "done")))

        assertContains(goal, "no markdown code blocks", ignoreCase = true, message = "Should specify no markdown")
        assertContains(assignment, "no markdown code blocks", ignoreCase = true, message = "Should specify no markdown")
        assertContains(progress, "no markdown code blocks", ignoreCase = true, message = "Should specify no markdown")
    }
}
