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

// ─────────────────────────────────────────────────────────────────────
// Task lifecycle events (spec §13)
// Plan 3 completes the full ~50-type taxonomy; Plan 2 adds only those
// emitted by the Runner during state transitions.
// ─────────────────────────────────────────────────────────────────────

data class TaskQueued(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String
) : BusEvent

data class TaskValidated(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val pass: Boolean,
	val rejectReason: String? = null
) : BusEvent

data class TaskStarted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val methodId: String,
	val pathId: String
) : BusEvent

data class TaskProgress(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String
) : BusEvent

data class TaskCompleted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val durationMillis: Long
) : BusEvent

data class TaskFailed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val reasonType: String,
	val reasonDetail: String,
	val attemptNumber: Int
) : BusEvent

data class TaskRetrying(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val attemptNumber: Int,
	val backoffMillis: Long
) : BusEvent

data class TaskSkipped(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val reason: String
) : BusEvent

data class TaskPaused(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val reason: String
) : BusEvent

data class TaskResumed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String
) : BusEvent

data class MethodPicked(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val methodId: String
) : BusEvent

data class PathPicked(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val pathId: String,
	val pathKind: String
) : BusEvent
