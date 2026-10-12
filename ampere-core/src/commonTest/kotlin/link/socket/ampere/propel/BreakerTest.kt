package link.socket.ampere.propel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
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
 * AMPR-440: the Breaker's fold, over the one rule it can evaluate today.
 *
 * `Rule.AccessPolicy` is evaluated by `PlugPermissionGate.check` unchanged, so what is
 * asserted here is the mapping (Allow is a pass and each deny is a trip naming the
 * permission), the fold (a Hard trip is final whatever came before it), and the failure
 * arm (a rule the Breaker cannot evaluate is never a pass). Publishing the denial is the
 * jvmTest `ExecutePlanStepAdmissionTest`'s, which drives a real door.
 */
class BreakerTest {

    @Test
    fun `a granted permission passes`() = runTest {
        val admission = breaker(UserGrants.granted(PERMISSION)).admit(PLUG_TOOL, Breaker.rulesFor(PLUG_TOOL))

        assertEquals(Admission.Pass, admission.getOrThrow())
    }

    @Test
    fun `a missing grant trips with the plug and the tool and the permission in the reason`() = runTest {
        val admission = breaker(UserGrants()).admit(PLUG_TOOL, Breaker.rulesFor(PLUG_TOOL))

        val trip = assertIs<Admission.Trip>(admission.getOrThrow())
        assertTrue(trip.reason.contains("MISSING_GRANT"), "got '${trip.reason}'")
        assertTrue(trip.reason.contains(PERMISSION.toString()), "got '${trip.reason}'")
        assertTrue(trip.reason.contains(MANIFEST.id.value), "got '${trip.reason}'")
        assertTrue(trip.reason.contains(PLUG_TOOL.id), "got '${trip.reason}'")
    }

    @Test
    fun `a revoked grant trips even when it is also granted`() = runTest {
        val grants = UserGrants(granted = listOf(PERMISSION), revoked = listOf(PERMISSION))

        val admission = breaker(grants).admit(PLUG_TOOL, Breaker.rulesFor(PLUG_TOOL))

        val trip = assertIs<Admission.Trip>(admission.getOrThrow())
        assertTrue(trip.reason.contains("REVOKED_GRANT"), "got '${trip.reason}'")
        assertTrue(trip.reason.contains(PERMISSION.toString()), "got '${trip.reason}'")
    }

    @Test
    fun `a rule the Breaker cannot evaluate is a failure naming the rule and never a pass`() = runTest {
        val deadline = Rule.Deadline(due = Instant.fromEpochMilliseconds(0))

        val admission = breaker(UserGrants.granted(PERMISSION)).admit(PLAIN_TOOL, listOf(deadline to Hardness.SOFT))

        val failure = assertIs<IllegalStateException>(admission.exceptionOrNull())
        assertTrue(failure.message.orEmpty().contains("Deadline"), "got '${failure.message}'")
    }

    @Test
    fun `a Hard trip ordered after a Soft resist still trips`() = runTest {
        val rules = listOf(
            Breaker.rulesFor(PLUG_TOOL).single().first to Hardness.SOFT,
            Breaker.rulesFor(PLUG_TOOL).single(),
        )

        val admission = breaker(UserGrants()).admit(PLUG_TOOL, rules)

        assertIs<Admission.Trip>(admission.getOrThrow())
    }

    @Test
    fun `Soft breaches alone resist by one unit each`() = runTest {
        val soft = Breaker.rulesFor(PLUG_TOOL).single().first to Hardness.SOFT

        val admission = breaker(UserGrants()).admit(PLUG_TOOL, listOf(soft, soft))

        assertEquals(Admission.Resist(Resistance(2.0)), admission.getOrThrow())
    }

    @Test
    fun `a grant provider that throws is a failure and not a pass`() = runTest {
        val breaker = Breaker(userGrants = { error("grant store unavailable") }, actorId = AGENT_ID)

        val admission = breaker.admit(PLUG_TOOL, Breaker.rulesFor(PLUG_TOOL))

        val failure = assertIs<IllegalStateException>(admission.exceptionOrNull())
        assertTrue(failure.message.orEmpty().contains("grant store unavailable"), "got '${failure.message}'")
    }

    @Test
    fun `an access label the manifest does not declare is a failure`() = runTest {
        val rule = Rule.AccessPolicy(required = setOf("NativeAction(actionId=undeclared)"))

        val admission = breaker(UserGrants.granted(PERMISSION)).admit(PLUG_TOOL, listOf(rule to Hardness.HARD))

        assertTrue(admission.isFailure, "got $admission")
    }

    @Test
    fun `a tool with no manifest has no rules and passes`() = runTest {
        assertTrue(Breaker.rulesFor(PLAIN_TOOL).isEmpty())

        val admission = breaker(UserGrants()).admit(PLAIN_TOOL, Breaker.rulesFor(PLAIN_TOOL))

        assertEquals(Admission.Pass, admission.getOrThrow())
    }

    private fun breaker(grants: UserGrants): Breaker =
        Breaker(userGrants = { grants }, actorId = AGENT_ID)

    private companion object {
        const val AGENT_ID = "breaker-test-agent"

        val PERMISSION: PlugPermission = PlugPermission.NativeAction("send-notification")
        val MANIFEST = PlugManifest(
            id = PlugId("example-notifier"),
            name = "Example Notifier",
            version = "1.0.0",
            requiredPermissions = listOf(PERMISSION),
        )

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
