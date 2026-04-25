package net.vital.plugins.buildcore.core.task

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A unit of intent in a plan — e.g., "Train Mining", "Do Waterfall Quest".
 *
 * A task ships one or more [methods]; the user picks which ones to use
 * via the plan. The Runner selects a method per tick via MethodSelector,
 * then a path within that method via PathSelector.
 *
 * Spec §3, §6, §7.
 */
interface Task {
	val id: TaskId
	val displayName: String
	val version: SemVer
	val moduleId: ModuleId
	val config: ConfigSchema
	val methods: List<Method>

	/** Tasks may override to tighten the stall deadline. Default 5min. */
	val stallThreshold: Duration get() = 5.minutes

	fun validate(ctx: TaskContext): ValidationResult
	fun onStart(ctx: TaskContext)
	fun step(ctx: TaskContext): StepResult
	fun isComplete(ctx: TaskContext): Boolean
	fun safeStop(ctx: TaskContext)

	/**
	 * Called by [net.vital.plugins.buildcore.core.antiban.breaks.BedtimeEscalator]
	 * when a Bedtime break has been deferred past its hard ceiling. The task
	 * is responsible for driving itself to a safe stopping state (bank, log
	 * out) and then surfacing that via [canStopNow]. Default no-op so existing
	 * tasks compile without change.
	 *
	 * Plan 4b spec §5.6.
	 */
	suspend fun requestEarlyStop(reason: net.vital.plugins.buildcore.core.events.EarlyStopReason) {}

	/**
	 * Plan 6a: Confidence stub-signal source for "expected entities visible".
	 * Default null = "no expectation" → ExpectedEntitiesVisible signal returns 1.0.
	 */
	fun expectedEntities(ctx: TaskContext): List<net.vital.plugins.buildcore.core.confidence.hints.EntityHint>? = null

	/**
	 * Plan 6a: Confidence stub-signal source for "position reasonable".
	 * Default null = "no expectation" → PositionReasonable signal returns 1.0.
	 */
	fun expectedArea(ctx: TaskContext): net.vital.plugins.buildcore.core.confidence.hints.AreaHint? = null

	/**
	 * Plan 6a: Confidence stub-signal source for "inventory delta expected".
	 * Default null = "no expectation" → InventoryDeltaExpected signal returns 1.0.
	 */
	fun expectedInventoryDelta(ctx: TaskContext): net.vital.plugins.buildcore.core.confidence.hints.InventoryDeltaHint? = null

	fun progressSignal(ctx: TaskContext): ProgressFingerprint

	/**
	 * Returns true only if stopping RIGHT NOW would not damage the
	 * account (e.g., no incoming combat damage, no mid-action loss).
	 *
	 * Default: conservative "no open interface, not in combat, HP OK".
	 * Tasks entering dangerous areas (wilderness, bossing) MUST override.
	 */
	fun canStopNow(ctx: TaskContext): Boolean

	/**
	 * Called by the Runner when the task enters RECOVERING state.
	 * Defaults to running the standard recovery pipeline (Plan 6).
	 */
	fun onUnknownState(ctx: TaskContext): RecoveryDecision =
		RecoveryDecision.ContinueStandardPipeline

	/** Structural validation — enforces each method's IRONMAN invariant. */
	fun validateStructure(): ValidationResult {
		methods.forEach { m ->
			val r = m.validateStructure()
			if (r is ValidationResult.Reject) return r
		}
		if (methods.isEmpty()) {
			return ValidationResult.Reject(
				"Task '$id' must have at least one method",
				RejectKind.CUSTOM
			)
		}
		return ValidationResult.Pass
	}
}

@JvmInline
value class TaskId(val raw: String) {
	init { require(raw.isNotBlank()) { "TaskId must not be blank" } }
}

@JvmInline
value class ModuleId(val raw: String) {
	init { require(raw.isNotBlank()) { "ModuleId must not be blank" } }
}

/** Simple major.minor.patch. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
	override fun compareTo(other: SemVer): Int = when {
		major != other.major -> major.compareTo(other.major)
		minor != other.minor -> minor.compareTo(other.minor)
		else -> patch.compareTo(other.patch)
	}
	override fun toString(): String = "$major.$minor.$patch"
	companion object {
		fun parse(raw: String): SemVer {
			val parts = raw.split(".").map { it.toInt() }
			require(parts.size == 3) { "SemVer must have 3 parts, got '$raw'" }
			return SemVer(parts[0], parts[1], parts[2])
		}
	}
}
