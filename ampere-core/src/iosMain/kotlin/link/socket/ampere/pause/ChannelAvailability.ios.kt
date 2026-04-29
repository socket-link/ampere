package link.socket.ampere.pause

import kotlin.reflect.KClass

/**
 * iOS stub. Real implementation in W1.5 will query
 * `UNUserNotificationCenter`, AVAudioSession voice availability, and the
 * Ampere shell's foreground state.
 */
actual class ChannelAvailability actual constructor() {

    actual fun available(): List<KClass<out EscalationChannel>> = emptyList()
}
