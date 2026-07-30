package link.socket.ampere.canon.adapter

import link.socket.ampere.canon.NativePayload

/** A tiny in-memory stand-in for a native store, shared by every CanonAdapter's tests. */
class FakeNativeStore(
    initial: Map<String, NativePayload> = emptyMap(),
) {
    private val rows = initial.toMutableMap()
    var failNextFetch: String? = null

    fun read(nativeId: String): NativePayload? = rows[nativeId]

    fun write(nativeId: String, payload: NativePayload) {
        rows[nativeId] = payload
    }

    fun fetch(nativeId: String): Result<NativePayload> {
        failNextFetch?.let { reason ->
            failNextFetch = null
            return Result.failure(IllegalStateException(reason))
        }
        return rows[nativeId]
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("no such native object: $nativeId"))
    }
}
