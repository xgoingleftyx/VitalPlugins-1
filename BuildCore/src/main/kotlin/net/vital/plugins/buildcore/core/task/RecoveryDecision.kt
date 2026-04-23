package net.vital.plugins.buildcore.core.task

/**
 * Returned by [Task.onUnknownState]. Tells the Runner what to do when
 * confidence drops or state is unrecognized.
 *
 * Spec §11 — full Recovery Pipeline is Plan 6. Plan 2 ships the
 * decision types so the Runner's state machine can be written with
 * recovery-aware transitions.
 */
sealed class RecoveryDecision {
	object ContinueStandardPipeline : RecoveryDecision() {
		override fun toString() = "ContinueStandardPipeline"
	}
	data class CustomSteps(val steps: List<RecoveryStep>) : RecoveryDecision()
	object FailImmediately : RecoveryDecision() {
		override fun toString() = "FailImmediately"
	}
	object Resume : RecoveryDecision() {
		override fun toString() = "Resume"
	}
}

/** A single step inside a task-custom recovery sequence. */
data class RecoveryStep(val description: String, val action: String, val args: Map<String, String> = emptyMap())
