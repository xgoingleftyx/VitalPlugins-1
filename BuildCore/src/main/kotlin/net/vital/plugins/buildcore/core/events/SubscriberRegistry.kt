package net.vital.plugins.buildcore.core.events

import net.vital.plugins.buildcore.core.logging.LoggerScope

/**
 * Append-only registry of log subscribers. Attach order is registration
 * order; no unregister — plugins attach once per session and detach at
 * session end.
 *
 * Spec §4.3.
 */
class SubscriberRegistry {

	private val subscribers = mutableListOf<LogSubscriber>()

	val all: List<LogSubscriber> get() = subscribers.toList()

	fun register(subscriber: LogSubscriber): SubscriberRegistry {
		subscribers += subscriber
		return this
	}

	fun attachAll(bus: EventBus, scope: LoggerScope) {
		subscribers.forEach { it.attach(bus, scope) }
	}

	suspend fun drainAll() {
		subscribers.forEach { it.drain() }
	}
}
