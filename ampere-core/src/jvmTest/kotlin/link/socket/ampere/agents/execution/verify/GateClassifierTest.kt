package link.socket.ampere.agents.execution.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The infra-versus-agent step (AMPR-309, task 3), one heuristic at a time. */
class GateClassifierTest {

    private fun outcome(evidence: GateEvidence): GateOutcome = GateClassifier.classify(evidence).outcome

    @Test
    fun `exit 0 is a pass and needs nothing else`() {
        assertEquals(GateOutcome.PASSED, outcome(GateEvidence(exitCode = 0)))
    }

    @Test
    fun `a reproducible non-zero exit on a sound host is the agent's`() {
        val classification = GateClassifier.classify(GateEvidence(exitCode = 1, reproduced = true))
        assertEquals(GateOutcome.FAILED, classification.outcome)
        assertEquals("exited 1, and again when re-run", classification.reason)
    }

    @Test
    fun `unknown defaults to the agent — a failure nobody re-ran is still failed`() {
        assertEquals(GateOutcome.FAILED, outcome(GateEvidence(exitCode = 1)))
        assertEquals(GateOutcome.FAILED, outcome(GateEvidence(exitCode = null)))
    }

    @Test
    fun `everything that kept the gate from a verdict about the code is the host's`() {
        assertEquals(GateOutcome.INFRASTRUCTURE, outcome(GateEvidence(exitCode = null, timedOut = true)))
        assertEquals(GateOutcome.INFRASTRUCTURE, outcome(GateEvidence(exitCode = null, unsetVariables = listOf("X"))))
        assertEquals(GateOutcome.INFRASTRUCTURE, outcome(GateEvidence(exitCode = null, notStarted = "no such tool")))
        assertEquals(GateOutcome.INFRASTRUCTURE, outcome(GateEvidence(exitCode = 127)))
        assertEquals(GateOutcome.INFRASTRUCTURE, outcome(GateEvidence(exitCode = 126)))
        val broken = GateEvidence(exitCode = 1, brokenPreconditions = listOf("jdk-21"))
        assertEquals(GateOutcome.INFRASTRUCTURE, outcome(broken))
        assertEquals(GateOutcome.INFRASTRUCTURE, outcome(GateEvidence(exitCode = 1, reproduced = false)))
        assertEquals(GateOutcome.INFRASTRUCTURE, outcome(GateEvidence(exitCode = 0, interrupted = true)))
    }

    @Test
    fun `reasons are composed from identifiers, never output`() {
        val unset = GateClassifier.classify(GateEvidence(exitCode = null, unsetVariables = listOf("SIMULATOR_UDID")))
        assertEquals("never started: \${env.SIMULATOR_UDID} is unset on this host", unset.reason)
        val broken = GateClassifier.classify(GateEvidence(exitCode = 2, brokenPreconditions = listOf("simulator")))
        assertTrue("`simulator`" in broken.reason, broken.reason)
    }
}
