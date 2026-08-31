package link.socket.ampere.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** AMPR-318 task 3 validation: the SPI is implementable inline and contravariant in its subject. */
class ProbeSuiteTest {

    private val nonBlankProbe = object : Probe<String> {
        override val id = ProbeId("non-blank")

        override suspend fun evaluate(subject: String): Verdict =
            if (subject.isNotBlank()) {
                Verdict.Holds()
            } else {
                Verdict.Violated(reason = "subject is blank")
            }
    }

    /** Subject type is [Any] — accepted below where a `Probe<String>` is expected. */
    private val anySubjectProbe = object : Probe<Any> {
        override val id = ProbeId("any-subject")

        override suspend fun evaluate(subject: Any): Verdict =
            Verdict.Warn(reason = "always warns on ${subject::class.simpleName}")
    }

    @Test
    fun `suite reports each probe verdict against the supplied subject id`() = runTest {
        val suite = ProbeSuite(listOf(nonBlankProbe))

        val reports = suite.evaluate(subjectId = "plan-1", subject = "a plan graph")

        assertEquals(
            listOf(
                ProbeReport(
                    probeId = ProbeId("non-blank"),
                    subjectId = "plan-1",
                    verdict = Verdict.Holds(),
                ),
            ),
            reports,
        )
    }

    @Test
    fun `a contravariant probe over Any runs where a string probe is expected`() = runTest {
        val suite = ProbeSuite<String>(listOf(nonBlankProbe, anySubjectProbe))

        val reports = suite.evaluate(subjectId = "plan-2", subject = "")

        assertEquals(2, reports.size)
        assertEquals(Verdict.Violated(reason = "subject is blank"), reports[0].verdict)
        assertEquals(Verdict.Warn(reason = "always warns on String"), reports[1].verdict)
    }

    @Test
    fun `registry lists and resolves registered probes`() {
        val registry = ProbeRegistry()
        registry.register(nonBlankProbe)
        registry.register(anySubjectProbe)

        assertEquals(listOf<Probe<*>>(nonBlankProbe, anySubjectProbe), registry.all())
        assertEquals(nonBlankProbe, registry.get(ProbeId("non-blank")))
        assertNull(registry.get(ProbeId("missing")))
    }

    @Test
    fun `registering the same id again replaces the earlier probe`() {
        val registry = ProbeRegistry()
        val replacement = object : Probe<String> {
            override val id = ProbeId("non-blank")

            override suspend fun evaluate(subject: String): Verdict = Verdict.Holds()
        }

        registry.register(nonBlankProbe)
        registry.register(replacement)

        assertEquals(1, registry.all().size)
        assertEquals(replacement, registry.get(ProbeId("non-blank")))
    }
}
