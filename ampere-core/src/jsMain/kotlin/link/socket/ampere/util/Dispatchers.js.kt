package link.socket.ampere.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// JS doesn't have Dispatchers.IO, use Default instead
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
