package net.vital.plugins.buildcore.core.restrictions

import net.vital.plugins.buildcore.core.task.AreaTag
import net.vital.plugins.buildcore.core.task.Skill

/**
 * Account-style constraints enforced cross-cutting.
 *
 * Spec §8. Sealed — new cases require code change by design.
 *
 * An account profile has a Set<Restriction>. The RestrictionEngine
 * vetoes any Effect that violates any Restriction.
 *
 * Mule restrictions are mutually exclusive — exactly one of
 * [NoMuleInteraction] / [MuleBondsOnly] / [MuleFull] in every set.
 */
sealed class Restriction {

	// XP
	data class XpCap(val skill: Skill, val maxLevel: Int) : Restriction() {
		init { require(maxLevel in 1..99) { "XpCap: maxLevel must be 1..99, was $maxLevel" } }
	}
	data class XpForbidden(val skill: Skill) : Restriction()
	data class QpCap(val maxQp: Int) : Restriction() {
		init { require(maxQp >= 0) { "QpCap: maxQp must be non-negative" } }
	}

	// Economy
	object NoGrandExchange : Restriction() { override fun toString() = "NoGrandExchange" }
	object NoPlayerTrade : Restriction() { override fun toString() = "NoPlayerTrade" }
	object NoDroppedLoot : Restriction() { override fun toString() = "NoDroppedLoot" }

	// Mule tier (exactly one of these three per profile)
	object NoMuleInteraction : Restriction() { override fun toString() = "NoMuleInteraction" }
	object MuleBondsOnly : Restriction() { override fun toString() = "MuleBondsOnly" }
	object MuleFull : Restriction() { override fun toString() = "MuleFull" }

	// Area
	object NoWilderness : Restriction() { override fun toString() = "NoWilderness" }
	object NoPvP : Restriction() { override fun toString() = "NoPvP" }
	data class NoArea(val area: AreaTag) : Restriction()

	// Safety (HCIM layer — deferred activation per spec §5)
	data class HpFleeFloor(val percent: Int) : Restriction() {
		init { require(percent in 0..100) { "HpFleeFloor: percent must be 0..100" } }
	}
	object NoHighRiskCombat : Restriction() { override fun toString() = "NoHighRiskCombat" }
	object HcimSafetyBundle : Restriction() { override fun toString() = "HcimSafetyBundle" }

	// Feature-flag
	object NoQuestsBeyondRequirements : Restriction() { override fun toString() = "NoQuestsBeyondRequirements" }
	object NoTasksThatRaiseCombatLevel : Restriction() { override fun toString() = "NoTasksThatRaiseCombatLevel" }

	// Escape hatch — avoid; prefer adding a first-class case
	data class CustomFlag(val tag: String) : Restriction()
}

/** Categories used by RestrictionSet to enforce the "exactly one mule tier" invariant. */
internal fun Restriction.isMuleTier(): Boolean = this is Restriction.NoMuleInteraction
	|| this is Restriction.MuleBondsOnly
	|| this is Restriction.MuleFull
