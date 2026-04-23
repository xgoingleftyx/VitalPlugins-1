package net.vital.plugins.buildcore.core.restrictions

import net.vital.plugins.buildcore.core.task.Skill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestrictionSetCompositionTest {

	@Test
	fun `archetype base + additional merges to a single set`() {
		val set = RestrictionSet.compose(
			archetype = Archetype.PURE_1DEF,
			additional = setOf(Restriction.NoWilderness),
			muleOverride = null
		)
		assertTrue(Restriction.XpCap(Skill.DEFENCE, 1) in set.restrictions)
		assertTrue(Restriction.NoWilderness in set.restrictions)
		assertTrue(Restriction.MuleFull in set.restrictions)
	}

	@Test
	fun `muleOverride replaces archetype mule tier`() {
		val set = RestrictionSet.compose(
			archetype = Archetype.PURE_1DEF,
			additional = emptySet(),
			muleOverride = Restriction.NoMuleInteraction
		)
		assertTrue(Restriction.NoMuleInteraction in set.restrictions)
		assertTrue(Restriction.MuleFull !in set.restrictions)
	}

	@Test
	fun `profile cannot remove archetype-imposed restriction via additional (additional only adds)`() {
		val set = RestrictionSet.compose(
			archetype = Archetype.IRONMAN,
			additional = setOf(Restriction.NoWilderness),
			muleOverride = null
		)
		// Ironman's NoGrandExchange survives even though additional doesn't include it
		assertTrue(Restriction.NoGrandExchange in set.restrictions)
	}

	@Test
	fun `invalid muleOverride that is not a mule tier is rejected`() {
		val ex = assertThrows(IllegalArgumentException::class.java) {
			RestrictionSet.compose(
				archetype = Archetype.MAIN,
				additional = emptySet(),
				muleOverride = Restriction.NoWilderness
			)
		}
		assertTrue(ex.message!!.contains("mule tier"), "got: ${ex.message}")
	}

	@Test
	fun `exactly one mule tier present in every composed set`() {
		val sets = listOf(Archetype.MAIN, Archetype.PURE_1DEF, Archetype.IRONMAN)
			.map { RestrictionSet.compose(it, emptySet(), muleOverride = null) }

		sets.forEach { set ->
			val muleTiers = set.restrictions.filter { it.isMuleTier() }
			assertEquals(1, muleTiers.size, "expected 1 mule tier in $set, got $muleTiers")
		}
	}

	@Test
	fun `HCIM archetype is flagged disabled`() {
		assertEquals(false, Archetype.HCIM.enabled)
	}

	@Test
	fun `HCIM archetype still composes correctly for future use`() {
		val set = RestrictionSet.compose(Archetype.HCIM, emptySet(), muleOverride = null)
		assertTrue(Restriction.NoWilderness in set.restrictions)
		assertTrue(Restriction.NoHighRiskCombat in set.restrictions)
		assertTrue(Restriction.HcimSafetyBundle in set.restrictions)
	}
}
