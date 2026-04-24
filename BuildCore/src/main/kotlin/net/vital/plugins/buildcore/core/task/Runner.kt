package net.vital.plugins.buildcore.core.task

import kotlinx.coroutines.delay
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.MethodPicked
import net.vital.plugins.buildcore.core.events.PathPicked
import net.vital.plugins.buildcore.core.events.TaskCompleted
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.events.TaskProgress
import net.vital.plugins.buildcore.core.events.TaskRetrying
import net.vital.plugins.buildcore.core.events.TaskSkipped
import net.vital.plugins.buildcore.core.events.TaskStarted
import net.vital.plugins.buildcore.core.events.TaskValidated
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import kotlin.time.Duration.Companion.milliseconds

/**
 * The single-threaded state-machine driver for a task instance.
 *
 * Plan 2's Runner handles one [TaskInstance] at a time through its
 * entire lifecycle and returns terminal state. Plan 2 does NOT
 * implement the plan-queue scheduler yet — that's part of Plan 7
 * (config/profile system) which loads the ordered task list.
 *
 * Lifecycle invocation order:
 *   PENDING → VALIDATE → STARTING → RUNNING (loop) → STOPPING → COMPLETED
 *
 * On [StepResult.Fail]:
 *   - if retries available: delay(backoff), increment attemptNumber, back to STARTING
 *   - else: STOPPING (for CRITICAL_PATH) or skip (for OPTIONAL) → terminal
 *
 * Spec §6.
 */
class Runner(
	private val bus: EventBus,
	private val sessionId: java.util.UUID
) {

	/**
	 * Run [instance] through its lifecycle until a terminal state.
	 * Returns the terminal [TaskState] (COMPLETED, FAILED, or skipped-as-FAILED).
	 */
	suspend fun run(
		instance: TaskInstance,
		restrictions: RestrictionSet,
		accountState: AccountState = AccountState(),
		taskConfig: Map<String, Any> = emptyMap(),
		methodConfig: Map<String, Any> = emptyMap()
	): TaskState {
		val startNanos = System.nanoTime()

		// PENDING → VALIDATE
		transition(instance, TaskState.VALIDATE)

		val ctx = SimpleTaskContext(
			sessionId = sessionId,
			taskInstanceId = instance.id,
			restrictions = restrictions,
			accountState = accountState,
			eventBus = bus,
			attemptNumber = instance.attemptNumber + 1,
			taskConfig = taskConfig,
			methodConfig = methodConfig
		)

		val validation = instance.task.validate(ctx)
		bus.emit(
			TaskValidated(
				sessionId = sessionId,
				taskInstanceId = instance.id,
				taskId = instance.task.id.raw,
				pass = validation is ValidationResult.Pass,
				rejectReason = (validation as? ValidationResult.Reject)?.reason
			)
		)
		if (validation is ValidationResult.Reject) {
			return fail(instance, "VALIDATE_REJECT", validation.reason, startNanos)
		}

		while (instance.attemptNumber < instance.retryPolicy.maxAttempts) {
			instance.attemptNumber += 1

			// Apply backoff before attempt 2+
			val delayBefore = instance.retryPolicy.delayBefore(instance.attemptNumber)
			if (delayBefore.inWholeMilliseconds > 0) {
				bus.emit(
					TaskRetrying(
						sessionId = sessionId,
						taskInstanceId = instance.id,
						taskId = instance.task.id.raw,
						attemptNumber = instance.attemptNumber,
						backoffMillis = delayBefore.inWholeMilliseconds
					)
				)
				delay(delayBefore)
			}

			// Pick a method + path. Plan 2: single-method tasks pick the sole method.
			val method = instance.task.methods.firstOrNull()
				?: return fail(instance, "NO_METHODS", "task has no methods", startNanos)
			bus.emit(
				MethodPicked(
					sessionId = sessionId,
					taskInstanceId = instance.id,
					taskId = instance.task.id.raw,
					methodId = method.id.raw
				)
			)

			val path = PathSelector.pick(method.paths, restrictions, accountState)
			if (path == null) {
				bus.emit(net.vital.plugins.buildcore.core.events.RestrictionViolated(
					sessionId = sessionId,
					taskInstanceId = instance.id,
					restrictionId = "composite",
					effectSummary = "no path allowed by restrictions/reqs for task=${instance.task.id.raw}",
					moment = net.vital.plugins.buildcore.core.events.RestrictionMoment.START
				))
				return fail(instance, "NO_ALLOWED_PATH", "no path allowed by restrictions/reqs", startNanos)
			}
			bus.emit(
				PathPicked(
					sessionId = sessionId,
					taskInstanceId = instance.id,
					taskId = instance.task.id.raw,
					pathId = path.id.raw,
					pathKind = path.kind.name
				)
			)

			// STARTING
			if (!instance.canTransition(TaskState.STARTING)) return instance.state
			transition(instance, TaskState.STARTING)
			instance.task.onStart(ctx)
			bus.emit(
				TaskStarted(
					sessionId = sessionId,
					taskInstanceId = instance.id,
					taskId = instance.task.id.raw,
					methodId = method.id.raw,
					pathId = path.id.raw
				)
			)

			// RUNNING loop
			transition(instance, TaskState.RUNNING)
			val running = runLoop(instance, ctx)

			if (running.success) {
				// STOPPING → COMPLETED
				transition(instance, TaskState.STOPPING)
				instance.task.safeStop(ctx)
				transition(instance, TaskState.COMPLETED)
				bus.emit(
					TaskCompleted(
						sessionId = sessionId,
						taskInstanceId = instance.id,
						taskId = instance.task.id.raw,
						durationMillis = (System.nanoTime() - startNanos) / 1_000_000
					)
				)
				return TaskState.COMPLETED
			}

			if (!running.retryable) {
				return fail(instance, running.failureType, running.failureDetail, startNanos)
			}
			// Otherwise loop to next attempt
		}

		// Retries exhausted
		return fail(instance, "RETRIES_EXHAUSTED", "max attempts reached", startNanos)
	}

	private data class LoopResult(
		val success: Boolean,
		val retryable: Boolean = false,
		val failureType: String = "",
		val failureDetail: String = ""
	)

	private suspend fun runLoop(instance: TaskInstance, ctx: TaskContext): LoopResult {
		while (true) {
			val stepResult = try {
				instance.task.step(ctx)
			} catch (ex: Exception) {
				return LoopResult(
					success = false,
					retryable = true,
					failureType = "EXCEPTION",
					failureDetail = ex.message ?: ex::class.simpleName ?: "unknown"
				)
			}

			when (stepResult) {
				is StepResult.Continue -> {
					instance.lastFingerprint = instance.task.progressSignal(ctx)
					bus.emit(
						TaskProgress(
							sessionId = sessionId,
							taskInstanceId = instance.id,
							taskId = instance.task.id.raw
						)
					)
					if (stepResult.cooldown.inWholeMilliseconds > 0) {
						delay(stepResult.cooldown)
					}
				}
				StepResult.Complete -> return LoopResult(success = true)
				is StepResult.Fail -> return LoopResult(
					success = false,
					retryable = stepResult.recoverable,
					failureType = stepResult.reason::class.simpleName ?: "Fail",
					failureDetail = stepResult.reason.toString()
				)
				is StepResult.Pause -> {
					transition(instance, TaskState.PAUSED)
					return LoopResult(
						success = false,
						retryable = false,
						failureType = "PAUSED",
						failureDetail = stepResult.reason
					)
				}
			}
		}
	}

	private fun transition(instance: TaskInstance, next: TaskState) {
		if (instance.canTransition(next)) {
			instance.setState(next)
		}
	}

	private suspend fun fail(instance: TaskInstance, type: String, detail: String, startNanos: Long): TaskState {
		if (instance.canTransition(TaskState.FAILED)) instance.setState(TaskState.FAILED)
		instance.lastFailure = FailureReason.Custom(type, detail)

		bus.emit(
			TaskFailed(
				sessionId = sessionId,
				taskInstanceId = instance.id,
				taskId = instance.task.id.raw,
				reasonType = type,
				reasonDetail = detail,
				attemptNumber = instance.attemptNumber
			)
		)

		// OPTIONAL tasks are skipped rather than session-ended
		if (instance.criticality == Criticality.OPTIONAL) {
			bus.emit(
				TaskSkipped(
					sessionId = sessionId,
					taskInstanceId = instance.id,
					taskId = instance.task.id.raw,
					reason = detail
				)
			)
		}
		return TaskState.FAILED
	}
}
