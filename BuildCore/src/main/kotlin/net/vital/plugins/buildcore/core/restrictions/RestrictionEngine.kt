package net.vital.plugins.buildcore.core.restrictions

import net.vital.plugins.buildcore.core.task.Effect
import net.vital.plugins.buildcore.core.task.Skill
import net.vital.plugins.buildcore.core.task.TradePurpose

/**
 * Pure-function engine that decides whether a given [Effect] is
 * compatible with a given [RestrictionSet].
 *
 * Spec §8. No side effects, no mutable state.
 *
 * Usage:
 *   - Edit-time validator (GUI hides tasks whose methods' effects vetoes)
 *   - Plan-start validator (full plan re-check)
 *   - Runtime validator (per-step sanity check)
 *   - Path/Method selectors (pick highest-rate *allowed* alternative)
 */
object RestrictionEngine {

	sealed class Result {
		object Allowed : Result() { override fun toString() = "Allowed" }
		data class Vetoed(val by: Restriction, val reason: String) : Result()
	}

	/**
	 * Check a single effect against a single restriction set. Returns
	 * the first restriction that vetoes, or [Result.Allowed] if none.
	 */
	fun isEffectAllowed(effect: Effect, set: RestrictionSet): Result {
		set.restrictions.forEach { r ->
			val veto = vetoFor(effect, r, set)
			if (veto != null) return Result.Vetoed(r, veto)
		}
		return Result.Allowed
	}

	/**
	 * Decide whether the full bundle of effects is allowed. Returns
	 * the first violation found.
	 */
	fun areEffectsAllowed(effects: Collection<Effect>, set: RestrictionSet): Result {
		effects.forEach { e ->
			val r = isEffectAllowed(e, set)
			if (r is Result.Vetoed) return r
		}
		return Result.Allowed
	}

	/** Returns a non-null reason string if [r] vetoes [effect]. */
	private fun vetoFor(effect: Effect, r: Restriction, set: RestrictionSet): String? = when (r) {
		is Restriction.XpCap -> xpCapVeto(effect, r)
		is Restriction.XpForbidden -> xpForbiddenVeto(effect, r)
		is Restriction.QpCap -> qpCapVeto(effect, r)
		Restriction.NoGrandExchange -> noGeVeto(effect)
		Restriction.NoPlayerTrade -> noPlayerTradeVeto(effect, set)
		Restriction.NoDroppedLoot -> null // checked by Plan 5 services; no Effect case for this
		Restriction.NoMuleInteraction -> noMuleVeto(effect)
		Restriction.MuleBondsOnly -> muleBondsOnlyVeto(effect)
		Restriction.MuleFull -> null // permissive
		Restriction.NoWilderness -> wildernessVeto(effect)
		Restriction.NoPvP -> pvpVeto(effect)
		is Restriction.NoArea -> if (effect is Effect.EntersArea && effect.area == r.area) {
			"effect enters forbidden area ${r.area}"
		} else null
		is Restriction.HpFleeFloor -> null // checked by Plan 5 Health service
		Restriction.NoHighRiskCombat -> null // advisory; tasks opt-in via Effect.CustomEffect("highRiskCombat")
		Restriction.HcimSafetyBundle -> null // bundle; individual restrictions do the work
		Restriction.NoQuestsBeyondRequirements -> null // advisory
		Restriction.NoTasksThatRaiseCombatLevel -> if (effect is Effect.RaisesCombatLevel) {
			"effect raises combat level (blocked by NoTasksThatRaiseCombatLevel)"
		} else null
		is Restriction.CustomFlag -> null // by convention, tasks test custom flags themselves
	}

	private fun xpCapVeto(effect: Effect, r: Restriction.XpCap): String? =
		if (effect is Effect.GrantsXp && effect.skill == r.skill) {
			"task grants ${r.skill} XP (capped at level ${r.maxLevel})"
		} else null

	private fun xpForbiddenVeto(effect: Effect, r: Restriction.XpForbidden): String? =
		if (effect is Effect.GrantsXp && effect.skill == r.skill) {
			"task grants ${r.skill} XP (forbidden)"
		} else null

	private fun qpCapVeto(effect: Effect, r: Restriction.QpCap): String? = null
	// QP cap enforced by plan-time validator against cumulative plan QP, not per-effect

	private fun noGeVeto(effect: Effect): String? =
		if (effect is Effect.UsesGrandExchange) "task uses Grand Exchange (blocked)" else null

	private fun noPlayerTradeVeto(effect: Effect, set: RestrictionSet): String? {
		if (effect !is Effect.TradesWithPlayer) return null
		// Bond reception is permitted under MuleBondsOnly even with NoPlayerTrade present
		if (effect.purpose == TradePurpose.MULE_RECEIVE_BOND && Restriction.MuleBondsOnly in set.restrictions) return null
		return "task trades with another player (blocked by NoPlayerTrade)"
	}

	private fun noMuleVeto(effect: Effect): String? =
		if (effect is Effect.TradesWithPlayer && isMuleTrade(effect.purpose)) {
			"task mule-trades (blocked by NoMuleInteraction)"
		} else null

	private fun muleBondsOnlyVeto(effect: Effect): String? {
		if (effect !is Effect.TradesWithPlayer) return null
		if (!isMuleTrade(effect.purpose)) return null
		return if (effect.purpose == TradePurpose.MULE_RECEIVE_BOND) null else {
			"MuleBondsOnly forbids ${effect.purpose}"
		}
	}

	private fun isMuleTrade(p: TradePurpose): Boolean = when (p) {
		TradePurpose.MULE_RECEIVE_GP, TradePurpose.MULE_RECEIVE_BOND,
		TradePurpose.MULE_SEND_GP, TradePurpose.MULE_RETURN -> true
	}

	private fun wildernessVeto(effect: Effect): String? =
		if (effect is Effect.EntersArea && effect.area == net.vital.plugins.buildcore.core.task.AreaTag.WILDERNESS) {
			"effect enters wilderness (blocked)"
		} else null

	private fun pvpVeto(effect: Effect): String? =
		if (effect is Effect.EntersArea && effect.area == net.vital.plugins.buildcore.core.task.AreaTag.PVP_WORLD) {
			"effect enters PvP world (blocked)"
		} else null
}
