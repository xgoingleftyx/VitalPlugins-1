package net.vital.plugins.buildcore.core.task

/**
 * Tags a task in a plan as either required-for-progression (CRITICAL_PATH)
 * or nice-to-have (OPTIONAL).
 *
 * Runner behavior on terminal failure:
 *  - CRITICAL_PATH → runner enters STOPPING mode; session-end.
 *  - OPTIONAL     → skip, runner picks next task.
 *
 * Spec §6.
 */
enum class Criticality {
	CRITICAL_PATH,
	OPTIONAL
}
