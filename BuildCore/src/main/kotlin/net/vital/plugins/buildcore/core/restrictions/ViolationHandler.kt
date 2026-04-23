package net.vital.plugins.buildcore.core.restrictions

/**
 * How the Runner responds to a restriction violation detected at
 * runtime (as opposed to edit-time, where the incompatible task is
 * hidden from the UI).
 *
 * Spec §8.
 */
enum class ViolationHandler {
	/** Task fails immediately with PermanentRestrictionViolated. */
	HARD_FAIL,

	/** Try the next ExecutionPath within the same method. */
	PATH_FALLBACK,

	/** Try the next Method within the same task. */
	METHOD_FALLBACK,

	/** Log and keep going (for purely advisory restrictions). */
	LOG_AND_CONTINUE
}
