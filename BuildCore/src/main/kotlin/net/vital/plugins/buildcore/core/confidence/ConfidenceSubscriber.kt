package net.vital.plugins.buildcore.core.confidence

import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.logging.BoundedChannelSubscriber
import java.util.UUID

/**
 * Plan 3-style subscriber. Forwards [ServiceCallEnd] events to
 * [ConfidenceTracker.onServiceCallEnd]. Plan 6a spec §8.1.
 */
class ConfidenceSubscriber(
	sessionIdProvider: () -> UUID
) : BoundedChannelSubscriber(
	name = "confidence",
	sessionIdProvider = sessionIdProvider,
	capacity = 256
)
{
	override suspend fun process(event: BusEvent)
	{
		if (event is ServiceCallEnd) ConfidenceTracker.onServiceCallEnd(event)
	}
}
