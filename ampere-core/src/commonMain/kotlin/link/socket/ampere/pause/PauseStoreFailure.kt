package link.socket.ampere.pause

/**
 * The caller named a [PauseCorrelationId] the store has never seen.
 *
 * Returned by [PauseStore.resolve] rather than swallowed, because a response arriving for an
 * unknown pause is the one failure a durable pause store must not hide: it means either the
 * raise never committed or the responder is quoting an id from somewhere else, and both are
 * worth a person's attention.
 */
class UnknownPauseException(
    val correlationId: PauseCorrelationId,
) : Exception("No pause is stored under correlation id '$correlationId'")

/**
 * [PauseStore.raise] was handed a [PauseCorrelationId] that already names a *different* pause, or
 * one whose decision has already settled.
 *
 * Re-raising a byte-identical pause that is still open succeeds instead: a retry of a raise that
 * may or may not have committed is expected in a system where the raiser can die mid-write, and
 * it is indistinguishable from the first attempt.
 *
 * What is refused is overwriting. A settled row holds a decision — possibly a person's — and
 * reusing its id would silently reopen it, which is worse than failing loudly. `INSERT OR REPLACE`
 * is therefore deliberately *not* what this store does, unlike the grant tables next to it.
 *
 * @property settled true when the existing row already has a response, false when the id is taken
 * by a different open pause.
 */
class PauseCorrelationIdInUseException(
    val correlationId: PauseCorrelationId,
    val settled: Boolean,
) : Exception(
    "Correlation id '$correlationId' already names " +
        if (settled) "a settled pause" else "a different open pause",
)

/**
 * A stored pause row exists but this build cannot decode its payload — version skew at the pause
 * boundary (AMPR-364's rule applied to `PauseStore`).
 *
 * Reachable without any third-party extension: adding an [EscalationChannel] variant is a
 * documented breaking change for readers, so an older binary reading a database a newer one wrote
 * hits exactly this. Distinct from [UnknownPauseException] — the row *is* there, and the caller
 * can tell "you named a pause that does not exist" from "this pause exists and I am too old to
 * read it".
 *
 * [PauseStore.listOpen] does not fail this way; it skips the row and says so on the bus. This is
 * what a single-row [PauseStore.get] returns, where there is no rest of the query to save. Note
 * that neither [PauseStore.resolve] nor [PauseStore.expire] can fail this way at all: both write
 * through the scalar columns, so an unreadable pause can still be answered and can still expire.
 */
class UndecodablePauseException(
    val correlationId: PauseCorrelationId,
    val reason: String,
    cause: Throwable? = null,
) : Exception("Stored pause '$correlationId' could not be decoded: $reason", cause)
