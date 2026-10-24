package link.socket.ampere.agents.execution.executor.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.environment.EnvironmentFileOperation

/**
 * What an agent streams, as the executor reports it (AMPR-492, D3): edits become `Writing`
 * once per path, other tool calls become `Analyzing`, a plan becomes `Planning`, and the
 * agent's words accumulate for the outcome.
 */
class HarnessTurnTest {

    private val fence = HarnessFence("/work", scope = null, policy = HarnessPolicy())
    private val epoch = Instant.fromEpochSeconds(0)

    private fun turn() = HarnessTurn(executorId = "fake-acp", fence = fence, now = { epoch })

    private fun edit(id: String, path: String) = SessionUpdate.ToolCall(
        toolCallId = id,
        title = "Edit",
        kind = ToolKind.EDIT,
        locations = listOf(ToolCallLocation(path)),
    )

    @Test
    fun `an edit tool call becomes one Writing per location with a workspace relative path`() {
        val turn = turn()

        val statuses = turn.collect(
            SessionUpdate.ToolCall(
                toolCallId = "c1",
                title = "Edit two files",
                kind = ToolKind.EDIT,
                locations = listOf(ToolCallLocation("/work/src/A.kt"), ToolCallLocation("/work/src/B.kt")),
            ),
        )

        assertEquals(listOf("src/A.kt", "src/B.kt"), statuses.map { assertIs<ExecutionStatus.Writing>(it).filePath })
        assertTrue(statuses.all { (it as ExecutionStatus.Writing).operation == EnvironmentFileOperation.MODIFY })
        assertTrue(statuses.all { it.executorId == "fake-acp" })
        assertEquals(setOf("src/A.kt", "src/B.kt"), turn.reportedEdits.keys)
    }

    @Test
    fun `a path already reported is not reported again on an update`() {
        val turn = turn()
        turn.collect(
            edit("c1", "/work/src/A.kt"),
        )

        val again = turn.collect(
            SessionUpdate.ToolCallUpdate(
                toolCallId = "c1",
                status = ToolCallStatus.COMPLETED,
                locations = listOf(ToolCallLocation("/work/src/A.kt")),
            ),
        )

        assertTrue(again.isEmpty(), "an update that adds no path adds no status")
    }

    @Test
    fun `a delete is a DELETE a move is a RENAME and a diff with no old text is a CREATE`() {
        val turn = turn()

        val delete = turn.collect(
            SessionUpdate.ToolCall(
                toolCallId = "c1",
                title = "Remove",
                kind = ToolKind.DELETE,
                locations = listOf(ToolCallLocation("/work/old.kt")),
            ),
        )
        val move = turn.collect(
            SessionUpdate.ToolCall(
                toolCallId = "c2",
                title = "Move",
                kind = ToolKind.MOVE,
                locations = listOf(ToolCallLocation("/work/new.kt")),
            ),
        )
        val create = turn.collect(
            SessionUpdate.ToolCallUpdate(
                toolCallId = "c3",
                content = listOf(
                    ToolCallContent(
                        type = ToolCallContent.DIFF,
                        path = "/work/fresh.kt",
                        oldText = null,
                        newText = "x",
                    ),
                ),
            ),
        )

        assertEquals(EnvironmentFileOperation.DELETE, assertIs<ExecutionStatus.Writing>(delete.single()).operation)
        assertEquals(EnvironmentFileOperation.RENAME, assertIs<ExecutionStatus.Writing>(move.single()).operation)
        assertEquals(EnvironmentFileOperation.CREATE, assertIs<ExecutionStatus.Writing>(create.single()).operation)
        assertEquals(EnvironmentFileOperation.CREATE, turn.operationFor("fresh.kt"))
    }

    @Test
    fun `a tool call that writes nothing is Analyzing with its title and an update to it is silent`() {
        val turn = turn()

        val first = turn.collect(
            SessionUpdate.ToolCall(toolCallId = "c1", title = "Search for callers", kind = ToolKind.SEARCH),
        )
        val later = turn.collect(SessionUpdate.ToolCallUpdate(toolCallId = "c1", status = ToolCallStatus.COMPLETED))

        assertEquals("Search for callers", assertIs<ExecutionStatus.Analyzing>(first.single()).description)
        assertTrue(later.isEmpty())
    }

    @Test
    fun `a plan is Planning with its entries and message chunks accumulate into the final message`() {
        val turn = turn()

        val planning = turn.collect(
            SessionUpdate.Plan(
                entries = listOf(PlanEntry("read the tests", status = "pending"), PlanEntry("fix the bug")),
            ),
        )
        val chunk1 = turn.collect(SessionUpdate.AgentMessageChunk(content = ContentBlock.text("Fixed ")))
        val chunk2 = turn.collect(SessionUpdate.AgentMessageChunk(content = ContentBlock.text("the bug.")))
        val other = turn.collect(SessionUpdate.Other(sessionUpdate = "agent_thought_chunk"))

        val strategy = assertIs<ExecutionStatus.Planning>(planning.single()).strategy
        assertEquals("[pending] read the tests; fix the bug", strategy)
        assertTrue(chunk1.isEmpty() && chunk2.isEmpty() && other.isEmpty())
        assertEquals("Fixed the bug.", turn.finalMessage)
    }

    @Test
    fun `a path outside the workspace is kept as given so the audit can name it`() {
        val turn = turn()

        val statuses = turn.collect(
            edit("c1", "/etc/hosts"),
        )

        assertEquals("/etc/hosts", assertIs<ExecutionStatus.Writing>(statuses.single()).filePath)
    }

    @Test
    fun `a served write is recorded with its operation and does not overwrite a reported one`() {
        val turn = turn()
        turn.collect(edit("c1", "/work/src/A.kt"))

        turn.recordServedWrite("src/A.kt", EnvironmentFileOperation.CREATE)
        turn.recordServedWrite("src/New.kt", EnvironmentFileOperation.CREATE)

        assertEquals(setOf("src/A.kt", "src/New.kt"), turn.servedWrites)
        assertEquals(EnvironmentFileOperation.MODIFY, turn.operationFor("src/A.kt"), "the first report wins")
        assertEquals(EnvironmentFileOperation.CREATE, turn.operationFor("src/New.kt"))
    }
}
