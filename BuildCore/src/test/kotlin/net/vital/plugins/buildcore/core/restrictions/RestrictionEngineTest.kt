package net.vital.plugins.buildcore.core.restrictions

import net.vital.plugins.buildcore.core.task.Effect
import net.vital.plugins.buildcore.core.task.GeAction
import net.vital.plugins.buildcore.core.task.Skill
import net.vital.plugins.buildcore.core.task.XpRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestrictionEngineTest {

	private val ironman = RestrictionSet.compose(Archetype.IRONMAN, emptySet(), muleOverride = null)
	private val pure1def = RestrictionSet.compose(Archetype.PURE_1DEF, emptySet(), muleOverride = null)
	private val main = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)

	@Test
	fun `ironman cannot use GE`() {
		val effect = Effect.UsesGrandExchange(GeAction.BUY)
		val result = RestrictionEngine.isEffectAllowed(effect, ironman)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}

	@Test
	fun `main account can use GE`() {
		val effect = Effect.UsesGrandExchange(GeAction.BUY)
		val result = RestrictionEngine.isEffectAllowed(effect, main)
		assertEquals(RestrictionEngine.Result.Allowed, result)
	}

	@Test
	fun `pure 1 def cannot gain defence XP that would push over level 1`() {
		val effect = Effect.GrantsXp(Skill.DEFENCE, XpRange(min = 100, max = 250))
		val result = RestrictionEngine.isEffectAllowed(effect, pure1def)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}

	@Test
	fun `pure can gain attack XP (no cap on attack)`() {
		val effect = Effect.GrantsXp(Skill.ATTACK, XpRange(min = 100, max = 250))
		val result = RestrictionEngine.isEffectAllowed(effect, pure1def)
		assertEquals(RestrictionEngine.Result.Allowed, result)
	}

	@Test
	fun `skiller cannot gain any combat XP`() {
		val skiller = RestrictionSet.compose(Archetype.SKILLER, emptySet(), muleOverride = null)
		val effect = Effect.GrantsXp(Skill.STRENGTH, XpRange(min = 10, max = 20))
		val result = RestrictionEngine.isEffectAllowed(effect, skiller)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}

	@Test
	fun `ironman cannot receive items from players`() {
		val effect = Effect.TradesWithPlayer(net.vital.plugins.buildcore.core.task.TradePurpose.MULE_RECEIVE_GP)
		val result = RestrictionEngine.isEffectAllowed(effect, ironman)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}

	@Test
	fun `mule bonds only allows bond trade but not gp trade`() {
		val effect = Effect.TradesWithPlayer(net.vital.plugins.buildcore.core.task.TradePurpose.MULE_RECEIVE_BOND)
		val result = RestrictionEngine.isEffectAllowed(effect, ironman)
		// Ironman has MuleBondsOnly; receiving a bond IS allowed even though
		// NoPlayerTrade is present — the bond purpose overrides.
		assertEquals(RestrictionEngine.Result.Allowed, result)
	}

	@Test
	fun `mule bonds only forbids gp mule delivery`() {
		val effect = Effect.TradesWithPlayer(net.vital.plugins.buildcore.core.task.TradePurpose.MULE_RECEIVE_GP)
		val result = RestrictionEngine.isEffectAllowed(effect, ironman)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}

	@Test
	fun `wilderness-entering effect blocked by NoWilderness`() {
		val set = RestrictionSet.compose(
			Archetype.MAIN, setOf(Restriction.NoWilderness), muleOverride = null
		)
		val effect = Effect.EntersArea(net.vital.plugins.buildcore.core.task.AreaTag.WILDERNESS)
		val result = RestrictionEngine.isEffectAllowed(effect, set)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}
}
