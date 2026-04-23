package net.vital.plugins.buildcore.core.task

/**
 * States in a task instance's lifecycle.
 *
 * Spec §6.
 *
 * Transition table (runner enforces):
 *
 *   PENDING     → VALIDATE
 *   VALIDATE    → STARTING | FAILED
 *   STARTING    → RUNNING | FAILED
 *   RUNNING     → STOPPING | RECOVERING | FAILED | PAUSED
 *   RECOVERING  → RUNNING | FAILED
 *   STOPPING    → COMPLETED | FAILED
 *   PAUSED      → RUNNING | FAILED (on resume)
 *   COMPLETED   → (terminal)
 *   FAILED      → (terminal)
 */
enum class TaskState {
	PENDING,
	VALIDATE,
	STARTING,
	RUNNING,
	STOPPING,
	RECOVERING,
	COMPLETED,
	FAILED,
	PAUSED;

	val isTerminal: Boolean get() = this == COMPLETED || this == FAILED
}
