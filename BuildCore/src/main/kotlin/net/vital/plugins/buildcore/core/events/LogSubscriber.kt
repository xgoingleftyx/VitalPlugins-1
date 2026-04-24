package net.vital.plugins.buildcore.core.events

import net.vital.plugins.buildcore.core.logging.LoggerScope

/**
 * Contract for everything that consumes events off the bus.
 *
 * Fast-path subscribers ([isFastPath] == true) collect directly on the
 * [LoggerScope] dispatcher so their handle() calls are serialised with
 * every other fast-path subscriber. Slow-path subscribers (see
 * [net.vital.plugins.buildcore.core.logging.BoundedChannelSubscriber])
 * still attach on the logger scope but hop to their own coroutine +
 * bounded channel so a stall cannot block the shared thread.
 *
 * Spec §4.4.
 */
interface LogSubscriber {

	/** Human-readable name for diagnostics and overflow tombstones. */
	val name: String

	/** `true` if this subscriber's handler runs on the logger thread directly. */
	val isFastPath: Boolean

	/** Launch the collector. Called by [SubscriberRegistry.attachAll]. */
	fun attach(bus: EventBus, loggerScope: LoggerScope)

	/** Flush & close resources. Called at session shutdown. */
	suspend fun drain()
}
