package net.vital.plugins.buildcore.core.task

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Mutable per-run wrapper around a [Task].
 *
 * Holds the runtime state (state, attemptNumber, lastFingerprint,
 * lastError). All mutations are serialized through the runner's
 * single-threaded execution loop, so no synchronization needed beyond
 * the atomic state reference (for watchdog reads).
 *
 * Spec §6.
 */
class TaskInstance(
	val id: UUID = UUID.randomUUID(),
	val task: Task,
	val criticality: Criticality,
	val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
) {
	private val _state = AtomicReference(TaskState.PENDING)
	val state: TaskState get() = _state.get()

	var attemptNumber: Int = 0
		internal set

	var lastFingerprint: ProgressFingerprint = ProgressFingerprint.EMPTY
		internal set

	var lastFailure: FailureReason? = null
		internal set

	/**
	 * Internal transition. Does NOT validate legality — the runner
	 * should check [canTransition] first and emit events around it.
	 */
	internal fun setState(next: TaskState) {
		_state.set(next)
	}

	/** Check whether [next] is a legal transition from the current state. */
	fun canTransition(next: TaskState): Boolean {
		val from = state
		return when (from) {
			TaskState.PENDING     -> next == TaskState.VALIDATE
			TaskState.VALIDATE    -> next == TaskState.STARTING || next == TaskState.FAILED
			TaskState.STARTING    -> next == TaskState.RUNNING || next == TaskState.FAILED
			TaskState.RUNNING     -> next == TaskState.STOPPING || next == TaskState.RECOVERING
				|| next == TaskState.FAILED || next == TaskState.PAUSED
			TaskState.RECOVERING  -> next == TaskState.RUNNING || next == TaskState.FAILED
			TaskState.STOPPING    -> next == TaskState.COMPLETED || next == TaskState.FAILED
			TaskState.PAUSED      -> next == TaskState.RUNNING || next == TaskState.FAILED
			TaskState.COMPLETED   -> false
			TaskState.FAILED      -> false
		}
	}

	override fun toString(): String =
		"TaskInstance(id=$id, task=${task.id}, state=$state, attempt=$attemptNumber)"
}
