package net.vital.plugins.buildcore.core.task

import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Orchestrates the Safe-Stop Contract (spec §6).
 *
 * When a stop is requested, we poll the task's [Task.canStopNow] up to
 * [maxDefer]. If it becomes true, we transition the instance to
 * [TaskState.STOPPING] and call [Task.safeStop]. If [maxDefer] elapses
 * without canStopNow returning true, we STILL call [Task.safeStop] —
 * "best-effort safe" is better than an abrupt kill. We record that
 * maxDefer was exceeded in the returned [Outcome].
 *
 * Hard exceptions (unhandled throwable in canStopNow, safeStop) are
 * the only paths that skip this contract — documented in spec §6.
 */
object SafeStopContract {

	data class Outcome(
		val completed: Boolean,
		val canStopNowReachedTrue: Boolean,
		val maxDeferExceeded: Boolean
	)

	suspend fun perform(
		instance: TaskInstance,
		ctx: TaskContext,
		pollInterval: Duration,
		maxDefer: Duration
	): Outcome {
		require(pollInterval > Duration.ZERO) { "pollInterval must be positive" }
		require(maxDefer >= Duration.ZERO) { "maxDefer must be non-negative" }

		val startNanos = System.nanoTime()
		ctx.eventBus.tryEmit(net.vital.plugins.buildcore.core.events.SafeStopRequested(
			sessionId = ctx.sessionId,
			taskInstanceId = ctx.taskInstanceId,
			reason = "stop requested for task=${instance.task.id.raw}"
		))

		val deadline = startNanos + maxDefer.inWholeNanoseconds
		var canStopReached = false
		while (System.nanoTime() < deadline) {
			if (instance.task.canStopNow(ctx)) {
				canStopReached = true
				break
			}
			delay(pollInterval)
		}

		// Transition to STOPPING and run the task's safe-stop routine.
		if (instance.canTransition(TaskState.STOPPING)) {
			instance.setState(TaskState.STOPPING)
		}
		instance.task.safeStop(ctx)

		val durationMs = (System.nanoTime() - startNanos) / 1_000_000
		ctx.eventBus.tryEmit(net.vital.plugins.buildcore.core.events.SafeStopCompleted(
			sessionId = ctx.sessionId,
			taskInstanceId = ctx.taskInstanceId,
			durationMillis = durationMs
		))

		return Outcome(
			completed = true,
			canStopNowReachedTrue = canStopReached,
			maxDeferExceeded = !canStopReached
		)
	}
}
