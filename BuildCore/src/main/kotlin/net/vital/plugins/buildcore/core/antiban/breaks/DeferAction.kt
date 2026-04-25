package net.vital.plugins.buildcore.core.antiban.breaks

/**
 * What [BreakScheduler] does when [TierConfig.maxDeferMs] expires before
 * [net.vital.plugins.buildcore.core.task.Task.canStopNow] returns true.
 *
 * Plan 4b spec §5.2.
 */
enum class DeferAction
{
	DROP,         // give up; emit BreakDropped; reset interval
	RESCHEDULE,   // emit BreakRescheduled; try again sooner
	ESCALATE      // call BedtimeEscalator (only Bedtime defaults to this)
}
