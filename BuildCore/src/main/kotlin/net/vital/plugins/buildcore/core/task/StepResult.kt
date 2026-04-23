package net.vital.plugins.buildcore.core.task

import kotlin.time.Duration

/**
 * Returned by [Task.step] on every tick. Drives the Runner's loop.
 *
 * Spec §6.
 */
sealed class StepResult {
	/** Continue running; caller schedules next tick after [cooldown]. */
	data class Continue(val cooldown: Duration = Duration.ZERO) : StepResult()

	/** Task has completed its work; transition to STOPPING. */
	object Complete : StepResult() { override fun toString() = "Complete" }

	/**
	 * Task failed with [reason]. Retry policy decides whether to
	 * schedule another attempt or mark FAILED terminally.
	 */
	data class Fail(val reason: FailureReason, val recoverable: Boolean = true) : StepResult()

	/** Task requests pause (e.g., user intervention needed). */
	data class Pause(val reason: String) : StepResult()
}

/** Categorization of why a step failed. Used by retry policy and telemetry. */
sealed class FailureReason {
	data class Transient(val detail: String) : FailureReason()
	data class PermanentRequirementUnmet(val detail: String) : FailureReason()
	data class PermanentRestrictionViolated(val detail: String) : FailureReason()
	data class UnknownState(val detail: String) : FailureReason()
	data class Exception(val throwable: Throwable) : FailureReason()
	data class Custom(val tag: String, val detail: String) : FailureReason()
}
