package link.socket.ampere.pause

import kotlin.reflect.KClass

/**
 * JS/browser stub. Real implementation in W1.5 may bridge the Notifications
 * API and the in-app card; voice and native push are typically unavailable.
 */
actual class ChannelAvailability actual constructor() {

    actual fun available(): List<KClass<out EscalationChannel>> = emptyList()
}
