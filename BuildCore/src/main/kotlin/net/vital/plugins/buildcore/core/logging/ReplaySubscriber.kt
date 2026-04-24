package net.vital.plugins.buildcore.core.logging

import net.vital.plugins.buildcore.core.events.BusEvent
import java.util.UUID

/**
 * Marker interface for the replay-recorder subscriber. Plan 3 ships
 * only the [NoOpReplaySubscriber] placeholder — Plan 4 provides the
 * real impl that records events + RNG state to `replays/<sid>.jsonl`.
 *
 * Spec §1, §14.
 */
interface ReplaySubscriber

class NoOpReplaySubscriber(
	sessionIdProvider: () -> UUID
) : BoundedChannelSubscriber(
	name = "replay-noop",
	sessionIdProvider = sessionIdProvider,
	capacity = 64
), ReplaySubscriber {

	override suspend fun process(event: BusEvent) {
		// no-op — the real Plan 4 subscriber records for byte-identical replay
	}
}
