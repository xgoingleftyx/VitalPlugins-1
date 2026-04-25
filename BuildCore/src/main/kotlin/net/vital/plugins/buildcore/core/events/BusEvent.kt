package net.vital.plugins.buildcore.core.events

import java.time.Instant
import java.util.UUID

/**
 * Marker for every event that flows through [EventBus].
 *
 * Every event carries correlation IDs: [eventId], [sessionId] are
 * always present; [taskInstanceId] and [moduleId] are nullable for
 * events without a task context (session lifecycle, performance).
 *
 * Events MUST be immutable data classes for thread-safe propagation
 * through [kotlinx.coroutines.flow.SharedFlow]. The [LayeringTest]
 * architecture test enforces immutability.
 *
 * Spec §5 (taxonomy), §13 (correlation IDs).
 */
sealed interface BusEvent {
	val eventId: UUID
	val timestamp: Instant
	val sessionId: UUID
	val schemaVersion: Int
	val taskInstanceId: UUID?
	val moduleId: String?
}

// ─────────────────────────────────────────────────────────────────────
// Test-only event (kept from Plan 1)
// ─────────────────────────────────────────────────────────────────────

internal data class TestPing(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val payload: String
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Task lifecycle (from Plan 2 — correlation IDs now go through the interface)
// ─────────────────────────────────────────────────────────────────────

data class TaskQueued(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String
) : BusEvent

data class TaskValidated(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val pass: Boolean,
	val rejectReason: String? = null
) : BusEvent

data class TaskStarted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val methodId: String,
	val pathId: String
) : BusEvent

data class TaskProgress(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String
) : BusEvent

data class TaskCompleted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val durationMillis: Long
) : BusEvent

data class TaskFailed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
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
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val attemptNumber: Int,
	val backoffMillis: Long
) : BusEvent

data class TaskSkipped(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val reason: String
) : BusEvent

data class TaskPaused(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val reason: String
) : BusEvent

data class TaskResumed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String
) : BusEvent

data class MethodPicked(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val methodId: String
) : BusEvent

data class PathPicked(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val pathId: String,
	val pathKind: String
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Session lifecycle (Plan 3 spec §5.2)
// ─────────────────────────────────────────────────────────────────────

enum class LaunchMode { NORMAL, HEADLESS, TEST }
enum class StopReason { USER, CRASH, SAFE_STOP, UPDATE }

data class SessionStart(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val buildcoreVersion: String,
	val archetype: String? = null,
	val launchMode: LaunchMode = LaunchMode.NORMAL
) : BusEvent

data class SessionEnd(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val reason: StopReason
) : BusEvent

data class TaskCounter(
	val started: Int = 0,
	val completed: Int = 0,
	val failed: Int = 0,
	val skipped: Int = 0
)

data class SessionSummary(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val durationMillis: Long,
	val taskCounts: Map<String, TaskCounter>,
	val totalEvents: Long
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Safe stop
// ─────────────────────────────────────────────────────────────────────

data class SafeStopRequested(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val reason: String
) : BusEvent

data class SafeStopCompleted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val durationMillis: Long
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Errors
// ─────────────────────────────────────────────────────────────────────

data class UnhandledException(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val threadName: String,
	val exceptionClass: String,
	val message: String,
	val stackTrace: String
) : BusEvent

data class ValidationFailed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val subject: String,
	val detail: String
) : BusEvent

enum class RestrictionMoment { EDIT, START, RUNTIME }

data class RestrictionViolated(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val restrictionId: String,
	val effectSummary: String,
	val moment: RestrictionMoment
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Performance
// ─────────────────────────────────────────────────────────────────────

data class PerformanceSample(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val intervalSeconds: Long,
	val eventRatePerSec: Double,
	val jvmHeapUsedMb: Long,
	val jvmHeapMaxMb: Long,
	val loggerLagMaxMs: Long,
	val droppedEventsSinceLastSample: Long
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Slow-subscriber overflow (generic; reused by Plan 4 & Plan 8)
// ─────────────────────────────────────────────────────────────────────

data class SubscriberOverflowed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val subscriberName: String,
	val droppedCount: Int
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Antiban events (Plan 4a spec §10)
// ─────────────────────────────────────────────────────────────────────

enum class InputKind {
	MOUSE_MOVE, MOUSE_CLICK,
	KEY_TAP, KEY_DOWN, KEY_UP, KEY_TYPE,
	CAMERA_ROTATE, CAMERA_PITCH
}

enum class InputMode { NORMAL, PRECISION, SURVIVAL }

data class InputAction(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val kind: InputKind,
	val targetX: Int? = null,
	val targetY: Int? = null,
	val durationMillis: Long,
	val mode: InputMode = InputMode.NORMAL
) : BusEvent

data class FatigueUpdated(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val sessionAgeMillis: Long,
	val reactionMultiplier: Double,
	val misclickMultiplier: Double,
	val overshootVarianceMultiplier: Double,
	val fidgetRateMultiplier: Double
) : BusEvent

data class PersonalityResolved(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val usernameHash: String,
	val generated: Boolean
) : BusEvent

data class SessionRngSeeded(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val seed: Long
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Antiban events (Plan 4b spec §8)
// ─────────────────────────────────────────────────────────────────────

enum class BreakTier { MICRO, NORMAL, BEDTIME, BANKING }
enum class EarlyStopReason { BEDTIME, USER, ORCHESTRATOR }
enum class MisclickKind { PIXEL_JITTER, NEAR_MISS }

data class PrecisionModeEntered(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val scopeId: Long,
	val mode: InputMode
) : BusEvent

data class PrecisionModeExited(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val scopeId: Long,
	val mode: InputMode,
	val durationMillis: Long
) : BusEvent

data class BreakScheduled(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val fireAtEpochMs: Long
) : BusEvent

data class BreakStarted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val plannedDurationMillis: Long
) : BusEvent

data class BreakEnded(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val actualDurationMillis: Long
) : BusEvent

data class BreakDeferred(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val deferredMillis: Long
) : BusEvent

data class BreakDropped(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val deferredMillis: Long
) : BusEvent

data class BreakRescheduled(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val newFireAtEpochMs: Long
) : BusEvent

data class BreakPreempted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val remainingMillis: Long
) : BusEvent

data class EarlyStopRequested(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val reason: EarlyStopReason
) : BusEvent

data class Misclick(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val kind: MisclickKind,
	val intendedX: Int,
	val intendedY: Int,
	val actualX: Int,
	val actualY: Int,
	val corrected: Boolean
) : BusEvent

data class SemanticMisclick(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val context: String,
	val intended: String,
	val actual: String
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Service call events (Plan 5a spec §4.1)
// ─────────────────────────────────────────────────────────────────────

enum class ServiceOutcome { SUCCESS, FAILURE, RESTRICTED, EXCEPTION }

data class ServiceCallStart(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val serviceName: String,
	val methodName: String,
	val callId: Long
) : BusEvent

data class ServiceCallEnd(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val serviceName: String,
	val methodName: String,
	val callId: Long,
	val durationMillis: Long,
	val outcome: ServiceOutcome
) : BusEvent
