package link.socket.ampere.util

import kotlin.js.Date
import kotlin.random.Random

actual fun randomUUID(): String {
    // Use crypto.randomUUID if available, otherwise fallback to manual generation
    return try {
        js("crypto.randomUUID()") as String
    } catch (e: dynamic) {
        // Fallback UUID v4 generation
        val chars = "0123456789abcdef"
        buildString {
            repeat(8) { append(chars[Random.nextInt(16)]) }
            append('-')
            repeat(4) { append(chars[Random.nextInt(16)]) }
            append('-')
            append('4') // Version 4
            repeat(3) { append(chars[Random.nextInt(16)]) }
            append('-')
            append(chars[(Random.nextInt(4) + 8)]) // Variant
            repeat(3) { append(chars[Random.nextInt(16)]) }
            append('-')
            repeat(12) { append(chars[Random.nextInt(16)]) }
        }
    }
}
