package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.LogSubscriber
import net.vital.plugins.buildcore.core.events.SubscriberOverflowed
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Base class for slow-path subscribers (Telemetry, Replay). The attach
 * step registers a fast-path listener on [LoggerScope] that does nothing
 * but tryOffer events into this subscriber's bounded channel. If the
 * channel is full, [BufferOverflow.DROP_OLDEST] silently drops the
 * oldest entries and increments a dropped-count; after the next
 * successful [process] call a single [SubscriberOverflowed] event
 * coalesces the dropped run.
 *
 * Spec §4.4.
 */
abstract class BoundedChannelSubscriber(
	override val name: String,
	private val sessionIdProvider: () -> UUID,
	private val capacity: Int = 1024
) : LogSubscriber {

	override val isFastPath: Boolean = false

	private val channel = Channel<BusEvent>(capacity, BufferOverflow.DROP_OLDEST)
	private val droppedCount = AtomicInteger(0)
	private var ownScope: CoroutineScope? = null
	@Volatile private var bus: EventBus? = null

	abstract suspend fun process(event: BusEvent)

	final override fun attach(bus: EventBus, loggerScope: LoggerScope) {
		this.bus = bus
		val myScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName(name))
		ownScope = myScope

		loggerScope.coroutineScope.launch {
			bus.events.collect { event ->
				if (channel.trySend(event).isFailure) droppedCount.incrementAndGet()
			}
		}

		myScope.launch {
			for (event in channel) {
				process(event)
				emitTombstoneIfNeeded()
			}
		}
	}

	final override suspend fun drain() {
		channel.close()
		ownScope?.cancel()
		emitTombstoneIfNeeded()
	}

	private fun emitTombstoneIfNeeded() {
		val dropped = droppedCount.getAndSet(0)
		if (dropped > 0) {
			bus?.tryEmit(SubscriberOverflowed(
				sessionId = sessionIdProvider(),
				subscriberName = name,
				droppedCount = dropped
			))
		}
	}
}
