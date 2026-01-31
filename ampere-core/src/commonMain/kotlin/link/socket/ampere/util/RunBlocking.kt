package link.socket.ampere.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Platform-specific blocking execution of coroutines.
 *
 * On JVM/Native: Uses kotlinx.coroutines.runBlocking
 * On JS: Throws UnsupportedOperationException (JS is single-threaded and cannot block)
 *
 * Note: For truly multiplatform code, prefer using suspend functions throughout.
 * This expect/actual is provided for compatibility with existing JVM patterns.
 */
expect fun <T> runBlockingCompat(
    context: CoroutineDispatcher? = null,
    block: suspend CoroutineScope.() -> T,
): T
