package link.socket.ampere.cli.ondevice

import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine

/**
 * Apple's on-device model as a [LocalInferenceEngine] any JVM host can hold, or why this host has
 * none (AMPR-469).
 *
 * The one public way to the helper engine. The CLI's [OnDeviceAssistant] binds through it, and so
 * does `ampere-desktop`, which renders `OnDeviceCognitionScreen` over
 * `OnDeviceInferenceSession.create(engine, database)`. It lives here rather than in `ampere-core`'s
 * `jvmMain` because every host that needs it already depends on this module. Moving it is a
 * promotion that can wait for a host that does not.
 *
 * Finding a helper spawns nothing: the helper starts on the engine's first probe. The caller owns
 * what [locate] returns and must close it, which ends the helper's process group.
 */
sealed interface OnDeviceHelperEngine : AutoCloseable {

    /** A helper was found, and [engine] reaches the model through it. */
    class Present internal constructor(
        val engine: LocalInferenceEngine,
        private val helper: AutoCloseable,
    ) : OnDeviceHelperEngine {
        /** Ends the helper for good. Idempotent. */
        override fun close() = helper.close()
    }

    /**
     * No helper on this host, so there is no on-device model.
     *
     * @property reason Said plainly, for the on-device indicator and a log line.
     */
    data class Absent(val reason: String) : OnDeviceHelperEngine {
        override fun close() = Unit
    }

    companion object {
        /** The helper this process can reach, or why there is none. Cheap: it only looks at the file system. */
        fun locate(): OnDeviceHelperEngine = locate(AmpereHelperLocator())

        internal fun locate(locator: AmpereHelperLocator): OnDeviceHelperEngine =
            when (val binary = locator.locate()) {
                is AmpereHelperBinary.Absent -> Absent(binary.reason)
                is AmpereHelperBinary.Present -> {
                    val helper = AmpereHelperProcess(binary.file)
                    Present(engine = HelperLocalInferenceEngine(helper), helper = helper)
                }
            }
    }
}
