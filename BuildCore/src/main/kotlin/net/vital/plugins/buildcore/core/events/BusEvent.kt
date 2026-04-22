package net.vital.plugins.buildcore.core.events

import java.time.Instant
import java.util.UUID

/**
 * Marker for every event that flows through [EventBus].
 *
 * Every event MUST carry correlation IDs. See spec §13 for the full
 * taxonomy — this class will acquire ~50 sealed subtypes in Plan 3.
 *
 * Events MUST be immutable data classes for thread-safe propagation
 * through [kotlinx.coroutines.flow.SharedFlow]. The [LayeringTest]
 * architecture test enforces this invariant.
 */
sealed interface BusEvent {
	val eventId: UUID
	val timestamp: Instant
	val sessionId: UUID
	val schemaVersion: Int
}

/**
 * Test-only event used to prove bus plumbing works. Plan 3 removes this
 * when the real event taxonomy lands.
 */
internal data class TestPing(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val payload: String
) : BusEvent
