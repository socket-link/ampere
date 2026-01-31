package link.socket.ampere.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * JS implementation of runBlockingCompat.
 *
 * JavaScript is single-threaded and cannot block, so this throws an exception.
 * Code that needs to run on JS should use suspend functions directly.
 */
actual fun <T> runBlockingCompat(
    context: CoroutineDispatcher?,
    block: suspend CoroutineScope.() -> T,
): T {
    throw UnsupportedOperationException(
        "runBlocking is not supported in JavaScript environments. " +
            "JS is single-threaded and cannot block. " +
            "Use suspend functions or delegate to a server-side component.",
    )
}
