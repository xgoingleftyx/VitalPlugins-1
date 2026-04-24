package net.vital.plugins.buildcore.core.logging

import net.vital.plugins.buildcore.core.events.BusEvent
import java.util.UUID

/**
 * Marker interface for the telemetry shipping subscriber. Plan 3 ships
 * only the [NoOpTelemetrySubscriber] placeholder — Plan 8 provides the
 * real impl that batches events and HTTP-POSTs to BuildCore-Server.
 *
 * Spec §1 ("Out of scope: real TelemetryClient"), §14.
 */
interface TelemetrySubscriber

/**
 * Drop-all placeholder. Wired into the registry at bootstrap so that
 * the BoundedChannelSubscriber plumbing is exercised end-to-end even
 * when no telemetry impl is present.
 */
class NoOpTelemetrySubscriber(
	sessionIdProvider: () -> UUID
) : BoundedChannelSubscriber(
	name = "telemetry-noop",
	sessionIdProvider = sessionIdProvider,
	capacity = 64
), TelemetrySubscriber {

	override suspend fun process(event: BusEvent) {
		// no-op — the real Plan 8 subscriber batches and POSTs
	}
}
