package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.restrictions.RestrictionEngine
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet

/**
 * Picks which [Method] to run next for a task that has multiple
 * selected methods (rotation pool).
 *
 * Spec §7.
 *
 * Plan 2 provides two rotation policies:
 *  - [RotationPolicy.WEIGHTED]         — always pick by weight
 *  - [RotationPolicy.WEIGHTED_NO_REPEAT] — pick by weight, but skip
 *    the method that matches [lastPickedId] if another is available
 *
 * Plan 4 adds [RotationPolicy.WEIGHTED_WITH_ANTIBAN_BIAS] which further
 * perturbs weights by the seeded personality vector.
 */
class MethodSelector(
	private val allMethods: List<Method>,
	private val selectedIds: Set<MethodId>,
	private val weights: Map<MethodId, Double>,
	private val rngSeed: Long = 0L
) {
	fun pickNext(
		restrictions: RestrictionSet,
		accountState: AccountState,
		lastPickedId: MethodId?,
		rotation: RotationPolicy
	): Method? {
		val eligible = allMethods
			.filter { it.id in selectedIds }
			.filter { it.validateStructure() is ValidationResult.Pass }
			.filter { methodAllowedByRestrictions(it, restrictions) }
			.filter { methodRequirementsMet(it, accountState) }

		if (eligible.isEmpty()) return null
		if (eligible.size == 1) return eligible.single()

		val pool = when (rotation) {
			RotationPolicy.WEIGHTED -> eligible
			RotationPolicy.WEIGHTED_NO_REPEAT -> {
				val filtered = eligible.filter { it.id != lastPickedId }
				if (filtered.isEmpty()) eligible else filtered
			}
			RotationPolicy.WEIGHTED_WITH_ANTIBAN_BIAS -> eligible // Plan 4 extends
		}

		return weightedPick(pool)
	}

	private fun methodAllowedByRestrictions(method: Method, restrictions: RestrictionSet): Boolean {
		val effectSet = method.effects + method.paths.flatMap { it.effects }
		return RestrictionEngine.areEffectsAllowed(effectSet, restrictions) is RestrictionEngine.Result.Allowed
	}

	private fun methodRequirementsMet(method: Method, state: AccountState): Boolean =
		method.requirements?.isSatisfied(state) ?: true

	private fun weightedPick(pool: List<Method>): Method {
		val weighted = pool.map { it to (weights[it.id] ?: 1.0).coerceAtLeast(0.0) }
		val total = weighted.sumOf { it.second }
		if (total <= 0.0) return pool.random(kotlin.random.Random(rngSeed))
		val rand = kotlin.random.Random(rngSeed).nextDouble() * total
		var running = 0.0
		weighted.forEach { (m, w) ->
			running += w
			if (rand < running) return m
		}
		return weighted.last().first
	}
}

enum class RotationPolicy {
	WEIGHTED,
	WEIGHTED_NO_REPEAT,
	WEIGHTED_WITH_ANTIBAN_BIAS
}
