package net.vital.plugins.buildcore.core.task

/**
 * Concrete execution strategy for a [Task].
 *
 * A task ships multiple methods (e.g., "Mining" task has methods for
 * iron at various locations, coal, granite 3-tick, etc.). User picks
 * one or more per task in the plan.
 *
 * Invariant enforced in [validate]: exactly one IRONMAN path with
 * no gating restrictions.
 *
 * Spec §4, §7.
 */
interface Method {
	val id: MethodId
	val displayName: String
	val description: String
	val paths: List<ExecutionPath>
	val requirements: Requirement?
	val effects: Set<Effect>
	val config: ConfigSchema
	val locationFootprint: Set<AreaTag>
	val risk: RiskProfile

	fun estimatedRate(accountState: AccountState): XpPerHour

	/**
	 * Structural validation — enforces the ironman-path invariant.
	 * Called by [ModuleRegistry] at registration time and by
	 * [PathSelector] before each run.
	 */
	fun validateStructure(): ValidationResult {
		val ironmanPaths = paths.filter { it.kind == PathKind.IRONMAN }
		if (ironmanPaths.size != 1) {
			return ValidationResult.Reject(
				"Method '$id' must have exactly 1 IRONMAN path, has ${ironmanPaths.size}",
				RejectKind.CUSTOM
			)
		}
		if (ironmanPaths.single().gatingRestrictions.isNotEmpty()) {
			return ValidationResult.Reject(
				"Method '$id' IRONMAN path must have no gatingRestrictions",
				RejectKind.CUSTOM
			)
		}
		return ValidationResult.Pass
	}
}

@JvmInline
value class MethodId(val raw: String) {
	init { require(raw.isNotBlank()) { "MethodId must not be blank" } }
}

enum class RiskProfile { NONE, LOW, MEDIUM, HIGH }
