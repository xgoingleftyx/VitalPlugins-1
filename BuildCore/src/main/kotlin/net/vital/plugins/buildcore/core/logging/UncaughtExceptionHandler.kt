package net.vital.plugins.buildcore.core.logging

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.UnhandledException
import java.util.UUID

/**
 * Install a JVM-wide uncaught exception handler that emits
 * [UnhandledException] for every throwable from a BuildCore-owned
 * thread. Uses [EventBus.tryEmit] — this handler runs outside any
 * coroutine and cannot suspend.
 *
 * If the bus buffer is saturated (no subscriber drained in time) the
 * event is dropped and [System.err] logs a last-resort line so the
 * exception still surfaces.
 *
 * The prior default handler is captured and chained so RuneLite's own
 * handler (if any) still sees the throwable.
 *
 * Spec §9.1.
 */
object UncaughtExceptionHandler {

	fun install(bus: EventBus, sessionIdProvider: () -> UUID) {
		val prior = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
			val event = UnhandledException(
				sessionId = sessionIdProvider(),
				threadName = thread.name,
				exceptionClass = throwable.javaClass.name,
				message = throwable.message ?: "",
				stackTrace = throwable.stackTraceToString()
			)
			val emitted = bus.tryEmit(event)
			if (!emitted) {
				System.err.println("[buildcore] bus full; UnhandledException dropped on thread '${thread.name}': ${throwable.javaClass.name}: ${throwable.message}")
			}
			prior?.uncaughtException(thread, throwable)
		}
	}
}
