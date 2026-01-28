package link.socket.ampere.util

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Platform-specific IO dispatcher for blocking operations.
 * On JVM/Android: Dispatchers.IO
 * On iOS: Dispatchers.Default (no IO dispatcher available)
 */
expect val ioDispatcher: CoroutineDispatcher
