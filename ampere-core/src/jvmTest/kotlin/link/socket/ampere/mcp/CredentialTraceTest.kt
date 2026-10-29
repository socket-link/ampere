package link.socket.ampere.mcp

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Instant
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.canon.CanonType
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.link.CredentialRef
import link.socket.ampere.link.EgressClass
import link.socket.ampere.link.InMemoryLinkStore
import link.socket.ampere.link.Link
import link.socket.ampere.link.LinkDirection
import link.socket.ampere.link.LinkId
import link.socket.ampere.link.LinkRequirement
import link.socket.ampere.link.LinkResolutionService
import link.socket.ampere.link.PlatformTarget
import link.socket.ampere.link.Transport
import link.socket.ampere.plug.McpServerDependency
import link.socket.ampere.plug.PlugContext
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.time.MutableClock

/**
 * The Link layer's first invariant with something that could now break it (AMPR-464):
 * a credential is bound, resolved, refreshed on connect, refreshed again after a 401,
 * and finally refused — and none of its secrets reaches a log line, a published event,
 * a stored event row, the persisted Link, or a failure message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialTraceTest {

    private val doorScope = TestScope(UnconfinedTestDispatcher())
    private val clock = MutableClock(Instant.parse("2026-10-29T12:00:00Z"))

    private val mcpUri = "https://mcp.invalid/mcp"
    private val plugId = PlugId("work-source-plug")
    private val requirement = LinkRequirement(
        name = "work-source",
        transport = Transport.MCP,
        direction = LinkDirection.READ_WRITE,
        minimumScope = setOf(CanonType.WORK_ITEM),
    )
    private val linkId = LinkId.forRequirement(requirement)

    private val manifest = PlugManifest(
        id = plugId,
        name = "Work Source",
        version = "1.0.0",
        requiredPermissions = listOf(PlugPermission.MCPServer(mcpUri)),
        mcpServers = listOf(McpServerDependency(name = "work-source", uri = mcpUri)),
        requiredLinks = listOf(requirement),
        emits = setOf(CanonType.WORK_ITEM),
        consumes = setOf(CanonType.WORK_ITEM),
    )

    @Test
    fun `a bind and every refresh leave no credential bytes in any trace`() = runBlocking<Unit> {
        val logLines = mutableListOf<String>()
        val capture = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                logLines += "$tag $message ${throwable?.stackTraceToString().orEmpty()}"
            }
        }
        val previousWriters = Logger.config.logWriterList
        val previousSeverity = Logger.config.minSeverity
        Logger.setMinSeverity(Severity.Verbose)
        Logger.addLogWriter(capture)

        try {
            InMemoryEventDoor.open(agentId = "link-resolution", scope = doorScope).use { door ->
                val store = InMemorySecretStore()
                val binding = KeychainMcpCredentialBinding(store)
                binding.bind(
                    linkId,
                    mcpUri,
                    McpCredential(
                        authToken = "fake-access-0",
                        refreshToken = "fake-refresh-0",
                        expiresAt = clock.now() - 1.minutes,
                        oauthClient = fakeClient,
                    ),
                ).getOrThrow()

                val link = Link(
                    id = linkId,
                    transport = Transport.MCP,
                    direction = LinkDirection.READ_WRITE,
                    egress = EgressClass.ThirdParty("work-source"),
                    scope = setOf(CanonType.WORK_ITEM),
                    credentialRef = CredentialRef.forLink(linkId),
                )
                val links = InMemoryLinkStore(links = listOf(link))
                links.grant(plugId, linkId, clock.now()).getOrThrow()

                val issuer = RotatingIssuer("fake-refresh-0", clock)
                val server = TokenCheckingServer("fake-access-1")

                // Connect: the seeded token is expired, so this refreshes to fake-access-1.
                val context = PlugContext.create(
                    manifest = manifest,
                    credentialBinding = binding,
                    linkResolutionService = LinkResolutionService(
                        linkStore = links,
                        platform = PlatformTarget.JVM_DESKTOP,
                        eventApi = door.api,
                    ),
                    connectionFactory = server.factory,
                    credentialRefresher = McpCredentialRefresher(binding, issuer, clock),
                ).getOrThrow()
                assertTrue(context.serverFailures.isEmpty(), "${context.serverFailures}")
                val client = assertNotNull(
                    context.mcpClientFor(context.availableTools().filterIsInstance<McpTool>().single()),
                )

                // A 401 mid-session: refreshed again, to fake-access-2, and retried.
                server.accepted.clear()
                server.accepted += "fake-access-2"
                client.callTool("get_issue", null).getOrThrow()

                // And refused outright: the third token is refused too, so the failure is typed.
                server.accepted.clear()
                val refusal = client.callTool("get_issue", null).exceptionOrNull()
                assertIs<McpCredentialException.Rejected>(refusal)

                val storedEvents = door.database.eventStoreQueries.getAllEvents().executeAsList()
                assertTrue(storedEvents.isNotEmpty(), "the trace should hold the Link resolution at least")

                val haystacks = buildList {
                    addAll(logLines)
                    storedEvents.forEach { row -> add(row.payload) }
                    add(DEFAULT_JSON.encodeToString(Link.serializer(), link))
                    links.list().getOrThrow().forEach { add(DEFAULT_JSON.encodeToString(Link.serializer(), it)) }
                    generateSequence<Throwable>(refusal) { it.cause }.forEach { add(it.toString()) }
                    add(binding.resolve(linkId, mcpUri).getOrThrow().toString())
                }

                val secrets = (0..3).flatMap { listOf("fake-access-$it", "fake-refresh-$it") } +
                    listOfNotNull(fakeClient.clientId, fakeClient.clientSecret)
                secrets.forEach { secret ->
                    haystacks.forEach { haystack ->
                        assertFalse(secret in haystack, "credential bytes '$secret' leaked into: $haystack")
                    }
                }

                // The secrets really were in play: the store holds the latest rotation.
                assertTrue(store.items.values.single().contains("fake-refresh-3"))
            }
        } finally {
            Logger.setLogWriters(previousWriters)
            Logger.setMinSeverity(previousSeverity)
        }
    }
}
