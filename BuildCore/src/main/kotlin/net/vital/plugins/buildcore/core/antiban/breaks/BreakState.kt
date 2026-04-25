package net.vital.plugins.buildcore.core.antiban.breaks

import java.util.concurrent.atomic.AtomicReference

/**
 * Internal mutable state of [BreakScheduler]. Single instance per scheduler.
 * Holds the currently-active break (if any) and lets SURVIVAL preempt it.
 *
 * Plan 4b spec §5.3, §5.5.
 */
internal class BreakState
{
	private val active = AtomicReference<ActiveBreak?>(null)

	fun startActive(tier: BreakTier, plannedDurationMs: Long, startedAtMs: Long)
	{
		active.set(ActiveBreak(tier, plannedDurationMs, startedAtMs))
	}

	fun clearActive()
	{
		active.set(null)
	}

	fun activeOrNull(): ActiveBreak? = active.get()

	internal data class ActiveBreak(val tier: BreakTier, val plannedDurationMs: Long, val startedAtMs: Long)
}
