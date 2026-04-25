package net.vital.plugins.buildcore.core.antiban.breaks

/**
 * Per-tier break configuration. All durations and intervals are user-tunable;
 * defaults shipped in [BreakConfig.defaults]. Persisted as `breaks.json`
 * under [net.vital.plugins.buildcore.core.logging.LogDirLayout.breakConfigDir].
 *
 * Plan 4b spec §5.2.
 */
data class TierConfig(
	val enabled: Boolean,
	val durationRangeMs: LongRange,
	val intervalRangeMs: LongRange,
	val maxDeferMs: Long,
	val onDeferTimeout: DeferAction
)

data class BreakConfig(
	val tiers: Map<BreakTier, TierConfig>
)
{
	companion object
	{
		fun defaults(): BreakConfig = BreakConfig(
			tiers = mapOf(
				BreakTier.MICRO to TierConfig(
					enabled = true,
					durationRangeMs  =       5_000L..       30_000L,    // 5–30s
					intervalRangeMs  =     180_000L..      720_000L,    // 3–12min
					maxDeferMs       =       60_000L,                   // 60s
					onDeferTimeout   = DeferAction.RESCHEDULE
				),
				BreakTier.NORMAL to TierConfig(
					enabled = true,
					durationRangeMs  =     180_000L..    1_200_000L,    // 3–20min
					intervalRangeMs  =   1_800_000L..    5_400_000L,    // 30–90min
					maxDeferMs       =     300_000L,                    // 5min
					onDeferTimeout   = DeferAction.RESCHEDULE
				),
				BreakTier.BEDTIME to TierConfig(
					enabled = true,
					durationRangeMs  =  21_600_000L..   36_000_000L,    // 6–10h
					intervalRangeMs  =  50_400_000L..   79_200_000L,    // 14–22h
					maxDeferMs       =   1_800_000L,                    // 30min
					onDeferTimeout   = DeferAction.ESCALATE
				),
				BreakTier.BANKING to TierConfig(
					enabled = true,
					durationRangeMs  =      20_000L..       60_000L,    // 20–60s
					intervalRangeMs  =           1L..            1L,    // trigger-driven; not interval-scheduled
					maxDeferMs       =       30_000L,                   // 30s
					onDeferTimeout   = DeferAction.DROP
				)
			)
		)
	}
}
