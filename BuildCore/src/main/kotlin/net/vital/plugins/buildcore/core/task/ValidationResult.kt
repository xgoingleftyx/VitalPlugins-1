package net.vital.plugins.buildcore.core.task

/**
 * Returned by [Task.validate]. Runner calls validate before STARTING.
 *
 * Spec §6, §7.
 */
sealed class ValidationResult {
	object Pass : ValidationResult() { override fun toString() = "Pass" }

	data class Reject(val reason: String, val kind: RejectKind) : ValidationResult()
}

enum class RejectKind {
	REQUIREMENT_UNMET,
	RESTRICTION_VIOLATED,
	INCOMPATIBLE_ARCHETYPE,
	CONFIG_INVALID,
	CUSTOM
}
