package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.restrictions.RestrictionSet

/**
 * Pure function: given a method's [paths], a profile's [restrictions],
 * and the current [accountState], pick the highest-rate allowed path
 * whose requirements are met.
 *
 * Contract (spec §4, §7):
 *   1. Sort paths by estimatedRate descending.
 *   2. Return the first path whose gatingRestrictions are all absent
 *      from [restrictions] AND whose requirements are all satisfied.
 *   3. If no path qualifies, return null.
 */
object PathSelector {
	fun pick(
		paths: List<ExecutionPath>,
		restrictions: RestrictionSet,
		accountState: AccountState
	): ExecutionPath? {
		return paths
			.sortedByDescending { it.estimatedRate.value }
			.firstOrNull { path ->
				pathIsAllowed(path, restrictions) && pathRequirementsMet(path, accountState)
			}
	}

	private fun pathIsAllowed(path: ExecutionPath, restrictions: RestrictionSet): Boolean =
		path.gatingRestrictions.none { it in restrictions }

	private fun pathRequirementsMet(path: ExecutionPath, state: AccountState): Boolean =
		path.requirements?.isSatisfied(state) ?: true
}
