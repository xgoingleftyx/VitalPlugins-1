package net.vital.plugins.buildcore.core.antiban.breaks

import net.vital.plugins.buildcore.core.events.BreakTier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BreakConfigTest
{
	@Test
	fun `default config has all four tiers enabled`()
	{
		val cfg = BreakConfig.defaults()
		assertEquals(4, cfg.tiers.size)
		BreakTier.values().forEach { tier ->
			assertTrue(cfg.tiers.containsKey(tier), "tier $tier missing")
		}
	}

	@Test
	fun `Bedtime defaults to ESCALATE on defer timeout`()
	{
		val cfg = BreakConfig.defaults()
		assertEquals(DeferAction.ESCALATE, cfg.tiers[BreakTier.BEDTIME]!!.onDeferTimeout)
	}

	@Test
	fun `Banking defaults to DROP on defer timeout`()
	{
		val cfg = BreakConfig.defaults()
		assertEquals(DeferAction.DROP, cfg.tiers[BreakTier.BANKING]!!.onDeferTimeout)
	}

	@Test
	fun `intervalRange is non-empty for non-trigger-driven tiers`()
	{
		val cfg = BreakConfig.defaults()
		assertTrue(cfg.tiers[BreakTier.MICRO]!!.intervalRangeMs.first > 0)
		assertTrue(cfg.tiers[BreakTier.NORMAL]!!.intervalRangeMs.first > 0)
		assertTrue(cfg.tiers[BreakTier.BEDTIME]!!.intervalRangeMs.first > 0)
	}
}
