package link.socket.ampere.pause

import kotlin.reflect.KClass

/**
 * Android stub. Real implementation in W1.5 will query NotificationManager
 * channel state, RecognizerIntent voice support, and the Ampere shell's
 * lifecycle state.
 */
actual class ChannelAvailability actual constructor() {

    actual fun available(): List<KClass<out EscalationChannel>> = emptyList()
}
