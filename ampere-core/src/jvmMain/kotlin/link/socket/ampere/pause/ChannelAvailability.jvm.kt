package link.socket.ampere.pause

import kotlin.reflect.KClass

/**
 * JVM/desktop stub. Real implementation in W1.5 may surface system tray
 * notifications and a desktop in-app card; voice and push are typically
 * unavailable.
 */
actual class ChannelAvailability actual constructor() {

    actual fun available(): List<KClass<out EscalationChannel>> = emptyList()
}
