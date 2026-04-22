package net.vital.plugins.buildcore.core.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The single event bus for the entire BuildCore runtime.
 *
 * Contract:
 *  - Implemented as a [MutableSharedFlow] with replay buffer 0 — late
 *    subscribers do NOT see past events. This is a deliberate choice:
 *    logging, telemetry, GUI status, and watchdog each attach at startup
 *    and consume forward.
 *  - Emission is non-blocking from the caller's perspective. Slow
 *    subscribers must buffer on their own side; see spec §13.
 *  - Events MUST be immutable data classes or objects. Enforced by
 *    architecture test [net.vital.plugins.buildcore.arch.LayeringTest].
 *
 * Thread safety: [MutableSharedFlow] is thread-safe for concurrent
 * emitters and collectors.
 */
class EventBus {

	private val _events = MutableSharedFlow<BusEvent>(
		replay = 0,
		extraBufferCapacity = 256
	)

	/** Read-side flow for subscribers. */
	val events: SharedFlow<BusEvent> = _events.asSharedFlow()

	/**
	 * Emit an event. Suspends only if extraBufferCapacity is exhausted
	 * AND at least one subscriber is present and slow. In typical use
	 * this returns immediately.
	 */
	suspend fun emit(event: BusEvent) {
		_events.emit(event)
	}

	/**
	 * Non-suspending emit. Returns false if the buffer was full and the
	 * event was dropped. Use for call sites that cannot suspend (e.g.,
	 * from inside JNI callbacks or fatal paths).
	 */
	fun tryEmit(event: BusEvent): Boolean = _events.tryEmit(event)
}
