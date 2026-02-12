package link.socket.ampere.repl

import sun.misc.Signal
import sun.misc.SignalHandler

/**
 * Manages signal handlers for the REPL.
 * Provides a clean interface for installing and managing OS signal handlers.
 */
class SignalHandlerManager {
    private val handlers = mutableMapOf<String, SignalHandler>()

    /**
     * Install a signal handler for the given signal name.
     * @param signalName The signal name (e.g., "INT", "TERM")
     * @param handler The action to perform when signal is received
     */
    fun installHandler(signalName: String, handler: (Signal) -> Unit) {
        val signalHandler = SignalHandler { signal -> handler(signal) }
        handlers[signalName] = signalHandler
        Signal.handle(Signal(signalName), signalHandler)
    }

    /**
     * Install SIGINT (Ctrl+C) handler for emergency exit.
     */
    fun installEmergencyExitHandler(exitMessage: String = "Emergency exit!") {
        installHandler("INT") { _ ->
            println("\n$exitMessage")
            kotlin.system.exitProcess(0)
        }
    }

    /**
     * Install SIGTERM handler for graceful shutdown.
     */
    fun installTerminationHandler(onTerminate: () -> Unit) {
        installHandler("TERM") { _ ->
            onTerminate()
        }
    }

    /**
     * Remove a signal handler and restore default behavior.
     */
    fun removeHandler(signalName: String) {
        handlers.remove(signalName)
        Signal.handle(Signal(signalName), SignalHandler.SIG_DFL)
    }

    /**
     * Clear all registered handlers.
     */
    fun clearAllHandlers() {
        handlers.keys.toList().forEach { signalName ->
            removeHandler(signalName)
        }
    }

    companion object {
        const val SIGINT = "INT"
        const val SIGTERM = "TERM"
        const val SIGHUP = "HUP"
    }
}
