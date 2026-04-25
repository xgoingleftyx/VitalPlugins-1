package net.vital.plugins.buildcore.core.antiban.breaks

import net.vital.plugins.buildcore.core.events.EarlyStopReason
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import java.util.UUID

/**
 * Drives the Bedtime escalation: tells the active task to wind itself down
 * via [Task.requestEarlyStop]. The task is responsible for finding a safe
 * terminus (bank, log out). The Runner state machine is unaffected — this is
 * cooperative.
 *
 * Plan 4b spec §5.4.
 */
class BedtimeEscalator(
	private val bus: EventBus,
	private val sessionIdProvider: () -> UUID
)
{
	suspend fun escalate(task: Task?, ctx: TaskContext?, cfg: TierConfig)
	{
		if (task == null) return
		task.requestEarlyStop(EarlyStopReason.BEDTIME)
		// The "fire anyway" branch is handled by BreakScheduler.applyTimeout —
		// after this method returns, the scheduler invokes executeBreak unconditionally.
	}
}
