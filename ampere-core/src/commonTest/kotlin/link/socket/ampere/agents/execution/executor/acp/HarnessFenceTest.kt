package link.socket.ampere.agents.execution.executor.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.ampere.agents.domain.cognition.FileAccessScope

/**
 * The deterministic gate in front of everything an ACP agent asks for (AMPR-492): paths are
 * judged against the workspace and the spark's scope, permission options are chosen by kind,
 * and no answer ever grants more than one call.
 */
class HarnessFenceTest {

    private val kotlinOnly = FileAccessScope(
        readPatterns = setOf(FileAccessScope.AnyPath),
        writePatterns = setOf("src/**/*.kt"),
        forbiddenPatterns = setOf("**/.env"),
    )

    private fun fence(
        scope: FileAccessScope? = kotlinOnly,
        policy: HarnessPolicy = HarnessPolicy(),
    ) = HarnessFence(workspaceRoot = "/work", scope = scope, policy = policy)

    @Test
    fun `a path inside the workspace relativizes and one outside or climbing out does not`() {
        val fence = fence()

        assertEquals("src/A.kt", fence.relativize("/work/src/A.kt"))
        assertEquals("src/A.kt", fence.relativize("/work/./src//A.kt"))
        assertEquals("src/A.kt", fence.relativize("src/A.kt"))
        assertEquals("", fence.relativize("/work"))
        assertNull(fence.relativize("/elsewhere/src/A.kt"))
        assertNull(fence.relativize("/work/../secrets.txt"))
        assertNull(fence.relativize("/work/src/../../x"))
        assertNull(fence.relativize("/workshop/A.kt"), "a sibling directory sharing the prefix is outside")
    }

    @Test
    fun `a trailing slash on the root changes nothing`() {
        val fence = HarnessFence("/work/", kotlinOnly, HarnessPolicy())

        assertEquals("/work", fence.root)
        assertEquals("src/A.kt", fence.relativize("/work/src/A.kt"))
    }

    @Test
    fun `writes are judged by the scope and refusals say why`() {
        val fence = fence()

        assertIs<HarnessFence.Verdict.Allowed>(fence.checkWrite("/work/src/main/A.kt"))
        val noPattern = assertIs<HarnessFence.Verdict.Refused>(fence.checkWrite("/work/README.md"))
        assertTrue(noPattern.reason.contains("no write pattern permits it"), noPattern.reason)
        val forbidden = assertIs<HarnessFence.Verdict.Refused>(fence.checkWrite("/work/src/.env"))
        assertTrue(forbidden.reason.contains("forbidden pattern \"**/.env\""), forbidden.reason)
        val outside = assertIs<HarnessFence.Verdict.Refused>(fence.checkWrite("/etc/passwd"))
        assertTrue(outside.reason.contains("outside the workspace"), outside.reason)
        val root = assertIs<HarnessFence.Verdict.Refused>(fence.checkWrite("/work"))
        assertTrue(root.reason.contains("workspace root"), root.reason)
    }

    @Test
    fun `reads are judged by the read patterns`() {
        val fence = fence()

        assertIs<HarnessFence.Verdict.Allowed>(fence.checkRead("/work/README.md"))
        assertIs<HarnessFence.Verdict.Refused>(fence.checkRead("/work/.env"))
        assertIs<HarnessFence.Verdict.Refused>(fence.checkRead("/other/README.md"))
    }

    @Test
    fun `a null scope is unconstrained inside the workspace and nothing outside it`() {
        val fence = fence(scope = null)

        assertIs<HarnessFence.Verdict.Allowed>(fence.checkWrite("/work/anything/at/all.txt"))
        assertIs<HarnessFence.Verdict.Refused>(fence.checkWrite("/work/../up.txt"))
        assertIs<HarnessFence.Verdict.Refused>(fence.checkRead("/other.txt"))
    }

    @Test
    fun `an edit inside the scope selects allow once and never allow always`() {
        val decision = fence().decide(
            request(
                call(ToolKind.EDIT, "/work/src/A.kt"),
            ),
        )

        assertEquals(HarnessFence.Decision.Select("allow"), decision)
    }

    @Test
    fun `an edit outside the scope selects the rejection and says why`() {
        val decision = fence().decide(
            request(
                call(ToolKind.EDIT, "/work/README.md"),
            ),
        )

        val select = assertIs<HarnessFence.Decision.Select>(decision)
        assertEquals("reject", select.optionId)
        assertTrue(select.refusedBecause!!.contains("README.md"), select.refusedBecause)
    }

    @Test
    fun `a diff names a path even when the call lists no location`() {
        val decision = fence().decide(
            request(
                PermissionToolCall(
                    toolCallId = "c",
                    kind = ToolKind.EDIT,
                    content = listOf(
                        ToolCallContent(type = ToolCallContent.DIFF, path = "/work/notes.txt", newText = "x"),
                    ),
                ),
            ),
        )

        assertEquals("reject", assertIs<HarnessFence.Decision.Select>(decision).optionId)
    }

    @Test
    fun `an edit with no path at all is allowed unless the scope permits no writes`() {
        val call = PermissionToolCall(toolCallId = "c", kind = ToolKind.DELETE)

        assertEquals(HarnessFence.Decision.Select("allow"), fence().decide(request(call)))
        val readOnly = fence(scope = FileAccessScope.ReadOnly).decide(request(call))
        assertTrue(assertIs<HarnessFence.Decision.Select>(readOnly).refusedBecause!!.contains("permits no writes"))
    }

    @Test
    fun `a call with no kind but with paths is judged as a write`() {
        val decision = fence().decide(
            request(PermissionToolCall(toolCallId = "c", locations = listOf(ToolCallLocation("/work/README.md")))),
        )

        assertEquals("reject", assertIs<HarnessFence.Decision.Select>(decision).optionId)
    }

    @Test
    fun `a read is judged by the read patterns and a search likewise`() {
        val inside = fence().decide(
            request(call(ToolKind.SEARCH, "/work/README.md")),
        )
        val secret = fence().decide(
            request(call(ToolKind.READ, "/work/.env")),
        )

        assertEquals(HarnessFence.Decision.Select("allow"), inside)
        assertEquals("reject", assertIs<HarnessFence.Decision.Select>(secret).optionId)
    }

    @Test
    fun `a shell command is the policy's to allow and is denied by default`() {
        val call = PermissionToolCall(toolCallId = "c", kind = ToolKind.EXECUTE, title = "run tests")

        val denied = fence().decide(request(call))
        val allowed = fence(policy = HarnessPolicy(shell = HarnessPolicy.ShellAccess.Allow)).decide(request(call))

        assertTrue(assertIs<HarnessFence.Decision.Select>(denied).refusedBecause!!.contains("shell commands"))
        assertEquals(HarnessFence.Decision.Select("allow"), allowed)
    }

    @Test
    fun `a think or fetch call is allowed`() {
        val think = fence().decide(request(PermissionToolCall(toolCallId = "c", kind = ToolKind.THINK)))
        val fetch = fence().decide(request(PermissionToolCall(toolCallId = "c", kind = ToolKind.FETCH)))

        assertEquals(HarnessFence.Decision.Select("allow"), think)
        assertEquals(HarnessFence.Decision.Select("allow"), fetch)
    }

    @Test
    fun `an agent offering only allow always has its turn cancelled`() {
        val decision = fence().decide(
            request(
                call(ToolKind.EDIT, "/work/src/A.kt"),
                options = listOf(PermissionOption("always", "Always", PermissionOptionKind.ALLOW_ALWAYS)),
            ),
        )

        val cancel = assertIs<HarnessFence.Decision.CancelTurn>(decision)
        assertTrue(cancel.reason.contains("allow_once"), cancel.reason)
    }

    @Test
    fun `a refusal with no reject option on offer cancels the turn`() {
        val decision = fence().decide(
            request(
                call(ToolKind.EDIT, "/work/README.md"),
                options = listOf(PermissionOption("allow", "Allow", PermissionOptionKind.ALLOW_ONCE)),
            ),
        )

        val cancel = assertIs<HarnessFence.Decision.CancelTurn>(decision)
        assertTrue(cancel.reason.contains("no reject option"), cancel.reason)
    }

    @Test
    fun `reject always is taken when reject once is not offered`() {
        val decision = fence().decide(
            request(
                call(ToolKind.EDIT, "/work/README.md"),
                options = listOf(
                    PermissionOption("allow", "Allow", PermissionOptionKind.ALLOW_ONCE),
                    PermissionOption("never", "Never", PermissionOptionKind.REJECT_ALWAYS),
                ),
            ),
        )

        assertEquals("never", assertIs<HarnessFence.Decision.Select>(decision).optionId)
    }

    @Test
    fun `absolutize roots a relative path at the workspace and leaves an absolute one alone`() {
        val fence = fence()

        assertEquals("/work/src/A.kt", fence.absolutize("src/A.kt"))
        assertEquals("/elsewhere/A.kt", fence.absolutize("/elsewhere/A.kt"))
    }

    private fun call(kind: String, path: String) = PermissionToolCall(
        toolCallId = "c",
        kind = kind,
        locations = listOf(ToolCallLocation(path)),
    )

    private fun request(
        call: PermissionToolCall,
        options: List<PermissionOption> = ScriptedAcpAgent.STANDARD_OPTIONS,
    ) = RequestPermissionParams(sessionId = "s", toolCall = call, options = options)
}
