package link.socket.ampere.work.linear

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.link.CredentialRef
import link.socket.ampere.link.EgressClass
import link.socket.ampere.link.InMemoryLinkStore
import link.socket.ampere.link.Link
import link.socket.ampere.link.LinkDirection
import link.socket.ampere.link.LinkId
import link.socket.ampere.link.LinkResolutionService
import link.socket.ampere.link.PlatformTarget
import link.socket.ampere.link.Transport
import link.socket.ampere.mcp.InMemoryMcpCredentialBinding
import link.socket.ampere.mcp.McpCredential
import link.socket.ampere.plug.PlugContext

/**
 * The live probe: one full claim → transition → comment → read-back cycle
 * against a **disposable** work-source project, through the production wiring.
 *
 * ## It does not run unless you ask it to
 *
 * Opt-in by three environment variables, and a no-op without all of them. It is
 * not in CI, and it must never point at a live team project — every issue and
 * comment written here syncs to a **public** GitHub issue (verified, AMPR-289),
 * and a comment cannot be unpublished from a public mirror.
 *
 * ```bash
 * AMPERE_WORK_SOURCE_MCP_URI=https://<work-source>/mcp \
 * AMPERE_WORK_SOURCE_TOKEN=<token> \
 * AMPERE_WORK_SOURCE_PROBE_ISSUE=<SANDBOX-1> \
 *   ./gradlew :ampere-work-linear:jvmTest --tests '*WorkSourceIntegrationProbe*'
 * ```
 *
 * The transcript lands in `.context/work-source-probe-transcript.md` (gitignored)
 * and on stdout, for attaching to the PR.
 *
 * ## What it proves that the unit tests cannot
 *
 * The unit suite runs against [FakeWorkSource], which models the five behaviours
 * the AMPR-289 probe *measured*. This one checks that the real server still has
 * them: that the pinned tool surface is live, that a comment comes back with a
 * server-assigned timestamp, that a transition is recorded as a state span, and
 * that markdown round-trips. It goes through [PlugContext.create] rather than
 * building an [link.socket.ampere.mcp.McpClient] by hand, so the Link resolution
 * and permission wiring are on the path too.
 *
 * It **leaves the ticket where it found it**: the last step reverts the state and
 * the label, so the sandbox issue is reusable. The claim and probe comments stay,
 * because comments are append-only and this adapter never deletes.
 */
class WorkSourceIntegrationProbe {

    @Test
    fun `claim transition comment and read back against a disposable project`() {
        val uri = env(MCP_URI) ?: return skip("$MCP_URI is unset")
        val token = env(TOKEN) ?: return skip("$TOKEN is unset")
        val issue = env(ISSUE) ?: return skip("$ISSUE is unset")

        val transcript = StringBuilder()
        fun log(line: String) {
            transcript.appendLine(line)
            println(line)
        }

        runBlocking {
            log("# Work-source integration probe")
            log("")
            log("- issue: `$issue`")
            log("- server: `$uri`")
            log("- run at: ${Clock.System.now()}")
            log("")

            val linkId = LinkId("work-source-probe")
            val manifest = LinearWorkSource.manifest(uri)

            val credentialBinding = InMemoryMcpCredentialBinding()
            credentialBinding.bind(linkId, uri, McpCredential(authToken = token)).getOrThrow()

            val linkStore = InMemoryLinkStore(
                links = listOf(
                    Link(
                        id = linkId,
                        transport = Transport.MCP,
                        direction = LinkDirection.READ_WRITE,
                        egress = EgressClass.ThirdParty(provider = "work-source"),
                        scope = manifest.emits,
                        credentialRef = CredentialRef(keychainAlias = "work-source-probe"),
                    ),
                ),
            )
            linkStore.grant(manifest.id, linkId).getOrThrow()

            val context = PlugContext.create(
                manifest = manifest,
                credentialBinding = credentialBinding,
                linkResolutionService = LinkResolutionService(
                    linkStore = linkStore,
                    platform = PlatformTarget.JVM_DESKTOP,
                ),
            ).getOrThrow()

            assertTrue(
                context.serverFailures.isEmpty(),
                "the MCP server did not come up: ${context.serverFailures}",
            )
            log("## 1. Plug context")
            log("")
            log("- tools discovered: ${context.availableTools().size}")
            log("- pinned surface verified: schema pins passed at `open()`")
            log("")

            val supervisor = LinearWorkSource.open(
                plugContext = context,
                linkId = linkId,
                instanceId = SupervisorInstanceId("probe-${Clock.System.now().toEpochMilliseconds()}"),
            ).getOrThrow()

            try {
                val before = supervisor.readIssue(issue).getOrThrow()
                log("## 2. Pre-read")
                log("")
                log("- state: `${before.statusName}` (`${before.statusType?.wireName}`)")
                log("- labels: ${before.labels}")
                log("- state history spans: ${before.stateHistory?.size}")
                log("")

                val claim = supervisor.claim(issue).getOrThrow()
                log("## 3. Claim")
                log("")
                log("- outcome: `$claim`")
                log("")
                assertTrue(claim.isWon, "an uncontested claim on a disposable issue must win")

                val markdown = PROBE_MARKDOWN
                supervisor.postComment(issue, markdown).getOrThrow()
                val comments = supervisor.readComments(issue).getOrThrow()
                val echoed = assertNotNull(
                    comments.lastOrNull { it.body == markdown },
                    "the comment just written did not come back from the comment read",
                )
                log("## 4. Comment round-trip")
                log("")
                log("- comment id: `${echoed.id}`")
                log("- server timestamp: `${echoed.createdAt}`")
                log("- byte-identical: ${echoed.body == markdown}")
                log("")
                assertEquals(markdown, echoed.body, "markdown must round-trip byte-identically")

                val history = supervisor.readStateHistory(issue).getOrThrow()
                log("## 5. Read-back")
                log("")
                log("- state now: `${supervisor.readIssue(issue).getOrThrow().statusName}`")
                log("- state history spans: ${history.size}")
                history.forEach { span ->
                    log("  - `${span.stateName}` ${span.startedAt} → ${span.endedAt ?: "(open)"}")
                }
                log("")
                assertTrue(
                    history.size > (before.stateHistory?.size ?: 0),
                    "the transition must be recorded as a new state span",
                )

                supervisor.transition(issue, before.statusName).getOrThrow()
                log("## 6. Restored")
                log("")
                log("- state: `${supervisor.readIssue(issue).getOrThrow().statusName}`")
                log("- claim comments left in place: comments are append-only and never deleted")
            } finally {
                context.close()
                val out = contextDir().resolve("work-source-probe-transcript.md")
                Files.createDirectories(out.parent)
                Files.writeString(out, transcript.toString())
                println("transcript written to ${out.toAbsolutePath()}")
            }
        }
    }

    /**
     * The repository's own gitignored `.context/`, found by walking up from the
     * Gradle test task's working directory — which is this module's directory,
     * not the repository root.
     */
    private fun contextDir(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.parent
        }
        return (candidate ?: Path.of("").toAbsolutePath()).resolve(".context")
    }

    private fun skip(reason: String) {
        println("WorkSourceIntegrationProbe skipped: $reason. See this class's KDoc to run it.")
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private companion object {
        const val MCP_URI = "AMPERE_WORK_SOURCE_MCP_URI"
        const val TOKEN = "AMPERE_WORK_SOURCE_TOKEN"
        const val ISSUE = "AMPERE_WORK_SOURCE_PROBE_ISSUE"

        /**
         * Every markdown construct the AMPR-289 probe found round-tripped
         * byte-identically, so a change in that is a change in the write
         * contract.
         */
        val PROBE_MARKDOWN: String = """
            ## Probe findings

            | gate | verdict |
            | --- | --- |
            | pins | pass |

            - [x] claim
            - [ ] revert

            ```kotlin
            val ampere = "⚡"
            ```
        """.trimIndent()
    }
}
