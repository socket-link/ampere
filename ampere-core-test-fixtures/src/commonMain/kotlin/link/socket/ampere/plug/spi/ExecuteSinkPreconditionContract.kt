package link.socket.ampere.plug.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Structural test suite every [ExecuteSink] must satisfy for conditional
 * writes: a [WritePreconditionKind] the sink does not declare in
 * [ExecuteSink.supportedPreconditions] is refused with
 * [ExecuteFailure.PreconditionUnsupported], never executed.
 *
 * Sinks that keep [ExecuteSink.executeIf]'s default pass trivially; the suite
 * exists for sinks that override it, where forgetting the declared-kind check
 * would silently honour — or silently ignore — a precondition the sink never
 * claimed to enforce.
 */
abstract class ExecuteSinkPreconditionContract<C> {

    /** The sink under test. */
    protected abstract fun sink(): ExecuteSink<C>

    /** Any command [sink] would accept unconditionally. */
    protected abstract fun command(): C

    /** A representative precondition of each kind; exhaustive so a new kind must be covered here. */
    protected open fun sampleOf(kind: WritePreconditionKind): WritePrecondition =
        when (kind) {
            WritePreconditionKind.MATCH_VERSION -> WritePrecondition.MatchVersion("__contract_etag__")
        }

    @Test
    fun `every undeclared precondition kind is refused as unsupported`() = runTest {
        val underTest = sink()
        val undeclared = WritePreconditionKind.entries - underTest.supportedPreconditions

        undeclared.forEach { kind ->
            val error = underTest.executeIf(command(), sampleOf(kind)).exceptionOrNull()

            val failure = assertIs<ExecuteException>(error, "kind $kind was not refused").failure
            val unsupported = assertIs<ExecuteFailure.PreconditionUnsupported>(failure)
            assertEquals(kind, unsupported.kind)
        }
    }
}
