package net.vital.plugins.buildcore.core.restrictions

import net.vital.plugins.buildcore.core.task.Skill

/**
 * Named preset bundle of [Restriction]s. Data, not code branches.
 *
 * Spec §5, §8.
 *
 * Adding a new archetype = adding an entry to [Archetype.builtins]. The
 * archetype's [baseRestrictions] MUST include exactly one mule tier.
 */
data class Archetype(
	val id: String,
	val displayName: String,
	val baseRestrictions: Set<Restriction>,
	val enabled: Boolean = true
) {
	init {
		val muleTiers = baseRestrictions.filter { it.isMuleTier() }
		require(muleTiers.size == 1) {
			"Archetype $id must have exactly one mule tier in baseRestrictions, got $muleTiers"
		}
	}

	companion object {
		val MAIN = Archetype(
			id = "main",
			displayName = "Main",
			baseRestrictions = setOf(Restriction.MuleFull)
		)

		val PURE_1DEF = Archetype(
			id = "pure.1def",
			displayName = "1 Defence Pure",
			baseRestrictions = setOf(
				Restriction.XpCap(Skill.DEFENCE, 1),
				Restriction.XpCap(Skill.PRAYER, 1),
				Restriction.MuleFull
			)
		)

		val PURE_60ATT = Archetype(
			id = "pure.60att",
			displayName = "60 Attack Pure",
			baseRestrictions = setOf(
				Restriction.XpCap(Skill.ATTACK, 60),
				Restriction.XpCap(Skill.DEFENCE, 1),
				Restriction.XpCap(Skill.PRAYER, 1),
				Restriction.MuleFull
			)
		)

		val ZERKER = Archetype(
			id = "zerker",
			displayName = "Berserker",
			baseRestrictions = setOf(
				Restriction.XpCap(Skill.DEFENCE, 45),
				Restriction.XpCap(Skill.PRAYER, 52),
				Restriction.XpCap(Skill.HITPOINTS, 80),
				Restriction.MuleFull
			)
		)

		val SKILLER = Archetype(
			id = "skiller",
			displayName = "Skiller",
			baseRestrictions = setOf(
				Restriction.XpForbidden(Skill.ATTACK),
				Restriction.XpForbidden(Skill.STRENGTH),
				Restriction.XpForbidden(Skill.DEFENCE),
				Restriction.XpForbidden(Skill.HITPOINTS),
				Restriction.XpForbidden(Skill.RANGED),
				Restriction.XpForbidden(Skill.MAGIC),
				Restriction.XpForbidden(Skill.PRAYER),
				Restriction.MuleFull
			)
		)

		val IRONMAN = Archetype(
			id = "ironman",
			displayName = "Ironman",
			baseRestrictions = setOf(
				Restriction.NoGrandExchange,
				Restriction.NoPlayerTrade,
				Restriction.NoDroppedLoot,
				Restriction.MuleBondsOnly
			)
		)

		val HCIM = Archetype(
			id = "hcim",
			displayName = "Hardcore Ironman",
			enabled = false,
			baseRestrictions = IRONMAN.baseRestrictions + setOf(
				Restriction.HcimSafetyBundle,
				Restriction.NoWilderness,
				Restriction.NoHighRiskCombat,
				Restriction.HpFleeFloor(50)
			)
		)

		val builtins: List<Archetype> = listOf(
			MAIN, PURE_1DEF, PURE_60ATT, ZERKER, SKILLER, IRONMAN, HCIM
		)

		fun findById(id: String): Archetype? = builtins.firstOrNull { it.id == id }
	}
}
