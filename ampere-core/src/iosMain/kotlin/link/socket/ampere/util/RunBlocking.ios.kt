package link.socket.ampere.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

actual fun <T> runBlockingCompat(
    context: CoroutineDispatcher?,
    block: suspend CoroutineScope.() -> T,
): T = if (context != null) {
    runBlocking(context, block)
} else {
    runBlocking(block = block)
}
