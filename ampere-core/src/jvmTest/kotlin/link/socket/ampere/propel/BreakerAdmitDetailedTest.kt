package link.socket.ampere.propel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.field.Admission
import link.socket.ampere.field.Hardness
import link.socket.ampere.field.Resistance
import link.socket.ampere.field.Rule
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.plug.permission.UserGrants

/**
 * AMPR-472 (F33): `Breaker.admitDetailed` is `admit` with the breached rules kept.
 *
 * The fold is the same one `BreakerTest` covers, so what is asserted here is only what the
 * detail adds: which rules breached, in what order, and which one a trip names — and that
 * keeping them changes nothing about the answer, including that nothing after a Hard trip is
 * evaluated. `ruleName` is the stable name the record carries each rule under.
 */
class BreakerAdmitDetailedTest {

    @Test
    fun `two Soft breaches resist with both rules listed in order`() = runTest {
        val rules = listOf(NOTIFY_RULE to Hardness.SOFT, LOCATE_RULE to Hardness.SOFT)

        val admitted = breaker(UserGrants()).admitDetailed(PLUG_TOOL, rules).getOrThrow()

        assertEquals(Admission.Resist(Resistance(2.0)), admitted.admission)
        assertEquals(rules, admitted.breached)
        assertNull(admitted.tripped, "a resist trips on nothing")
    }

    @Test
    fun `a trip lists the Soft breach before it and ends with the Hard rule it names`() = runTest {
        // The Deadline after the trip has no evaluation and would fail the admission if it
        // were reached: the trip is still final.
        val rules = listOf(
            NOTIFY_RULE to Hardness.SOFT,
            LOCATE_RULE to Hardness.HARD,
            Rule.Deadline(due = Instant.fromEpochMilliseconds(0)) to Hardness.HARD,
        )

        val admitted = breaker(UserGrants()).admitDetailed(PLUG_TOOL, rules).getOrThrow()

        assertIs<Admission.Trip>(admitted.admission)
        assertEquals(listOf(NOTIFY_RULE to Hardness.SOFT, LOCATE_RULE to Hardness.HARD), admitted.breached)
        assertEquals(LOCATE_RULE, admitted.tripped)
    }

    @Test
    fun `a pass breaches nothing`() = runTest {
        val grants = UserGrants.granted(NOTIFY, LOCATE)

        val admitted = breaker(grants).admitDetailed(PLUG_TOOL, listOf(NOTIFY_RULE to Hardness.HARD)).getOrThrow()

        assertEquals(Admission.Pass, admitted.admission)
        assertTrue(admitted.breached.isEmpty())
        assertNull(admitted.tripped)
    }

    @Test
    fun `admit answers what admitDetailed decides`() = runTest {
        val cases = listOf(
            UserGrants.granted(NOTIFY, LOCATE) to listOf(NOTIFY_RULE to Hardness.HARD),
            UserGrants() to listOf(NOTIFY_RULE to Hardness.SOFT, LOCATE_RULE to Hardness.SOFT),
            UserGrants() to listOf(NOTIFY_RULE to Hardness.SOFT, LOCATE_RULE to Hardness.HARD),
        )

        cases.forEach { (grants, rules) ->
            val breaker = breaker(grants)
            assertEquals(
                breaker.admitDetailed(PLUG_TOOL, rules).getOrThrow().admission,
                breaker.admit(PLUG_TOOL, rules).getOrThrow(),
            )
        }
    }

    @Test
    fun `an admission that cannot be decided is a failure with no detail`() = runTest {
        val deadline = Rule.Deadline(due = Instant.fromEpochMilliseconds(0))

        val admitted = breaker(UserGrants()).admitDetailed(PLAIN_TOOL, listOf(deadline to Hardness.SOFT))

        assertIs<IllegalStateException>(admitted.exceptionOrNull())
    }

    @Test
    fun `each rule has a stable name`() {
        assertEquals("AccessPolicy:example-device", Breaker.ruleName(NOTIFY_RULE, PLUG_TOOL))
        assertEquals("AccessPolicy", Breaker.ruleName(NOTIFY_RULE, PLAIN_TOOL))
        assertEquals("AccessPolicy", Breaker.ruleName(NOTIFY_RULE, tool = null))
        assertEquals("Quota.Tokens", Breaker.ruleName(Rule.Quota.Tokens(limit = 500), tool = null))
        assertEquals("Quota.Time", Breaker.ruleName(Rule.Quota.Time(limit = 10.minutes), tool = null))
        assertEquals(
            "Quota.Count:steps",
            Breaker.ruleName(Rule.Quota.Count(limit = 2, of = RunQuotas.STEPS), tool = null),
        )
        assertEquals("Transition", Breaker.ruleName(Rule.Transition(allowed = emptyMap()), tool = null))
        assertEquals("Requirements", Breaker.ruleName(Rule.Requirements(), tool = null))
        assertEquals("Dependency", Breaker.ruleName(Rule.Dependency(dependsOn = emptySet()), tool = null))
        assertEquals(
            "Deadline",
            Breaker.ruleName(Rule.Deadline(due = Instant.fromEpochMilliseconds(0)), tool = null),
        )
        assertEquals("Cadence", Breaker.ruleName(Rule.Cadence(every = 10.minutes), tool = null))
        assertEquals(
            "Window",
            Breaker.ruleName(
                Rule.Window(from = LocalTime(22, 0), until = LocalTime(7, 0), timezone = "America/Chicago"),
                tool = PLAIN_TOOL,
            ),
        )
    }

    @Test
    fun `a rule's limit is not part of its name`() {
        assertEquals(
            Breaker.ruleName(Rule.Quota.Tokens(limit = 1), tool = null),
            Breaker.ruleName(Rule.Quota.Tokens(limit = 1_000_000), tool = null),
        )
    }

    private fun breaker(grants: UserGrants): Breaker =
        Breaker(userGrants = { grants }, actorId = AGENT_ID)

    private companion object {
        const val AGENT_ID = "breaker-detail-test-agent"

        val NOTIFY: PlugPermission = PlugPermission.NativeAction("send-notification")
        val LOCATE: PlugPermission = PlugPermission.NativeAction("read-location")
        val MANIFEST = PlugManifest(
            id = PlugId("example-device"),
            name = "Example Device",
            version = "1.0.0",
            requiredPermissions = listOf(NOTIFY, LOCATE),
        )

        /** Two access rules over the same plug, each asking for one of its permissions. */
        val NOTIFY_RULE = Rule.AccessPolicy(required = setOf(Breaker.accessLabel(NOTIFY)))
        val LOCATE_RULE = Rule.AccessPolicy(required = setOf(Breaker.accessLabel(LOCATE)))

        val PLUG_TOOL: Tool<*> = tool(id = "notify", manifest = MANIFEST)
        val PLAIN_TOOL: Tool<*> = tool(id = "search", manifest = null)

        fun tool(id: String, manifest: PlugManifest?): Tool<*> = FunctionTool<ExecutionContext>(
            id = id,
            name = id,
            description = "test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            plugManifest = manifest,
            executionFunction = { error("the Breaker never runs a tool") },
        )
    }
}
