package net.vital.plugins.buildcore.core.antiban.breaks

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BreakConfigStoreTest
{
	@Test
	fun `load returns defaults when file does not exist`(@TempDir dir: Path)
	{
		val cfg = BreakConfigStore(dir).load()
		assertEquals(BreakConfig.defaults(), cfg)
	}

	@Test
	fun `save then load round-trips`(@TempDir dir: Path)
	{
		val original = BreakConfig.defaults()
		val store = BreakConfigStore(dir)
		store.save(original)
		val loaded = store.load()
		assertEquals(original, loaded)
	}

	@Test
	fun `missing tier in file falls back to defaults`(@TempDir dir: Path)
	{
		val partial = BreakConfig(tiers = mapOf(
			net.vital.plugins.buildcore.core.events.BreakTier.MICRO to TierConfig(
				enabled = false,
				durationRangeMs = 1L..2L,
				intervalRangeMs = 3L..4L,
				maxDeferMs = 5L,
				onDeferTimeout = DeferAction.DROP
			)
		))
		BreakConfigStore(dir).save(partial)
		val loaded = BreakConfigStore(dir).load()
		assertEquals(false, loaded.tiers[net.vital.plugins.buildcore.core.events.BreakTier.MICRO]!!.enabled)
		// Other tiers fall back to defaults
		assertEquals(BreakConfig.defaults().tiers[net.vital.plugins.buildcore.core.events.BreakTier.NORMAL],
			loaded.tiers[net.vital.plugins.buildcore.core.events.BreakTier.NORMAL])
	}
}
