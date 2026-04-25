package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogFinding
import net.vital.plugins.buildcore.core.events.WatchdogKind
import net.vital.plugins.buildcore.core.task.ProgressFingerprint
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext

class StallCheck(
	private val taskProvider: () -> Pair<Task?, TaskContext?>
) : WatchdogCheck
{
	private var lastFingerprint: ProgressFingerprint? = null
	private var lastChangedAtMs: Long = Long.MAX_VALUE

	override fun tick(nowMs: Long): WatchdogFinding?
	{
		val (task, ctx) = taskProvider()
		if (task == null || ctx == null) { lastFingerprint = null; lastChangedAtMs = nowMs; return null }
		val fp = task.progressSignal(ctx)
		if (fp != lastFingerprint)
		{
			lastFingerprint = fp
			lastChangedAtMs = nowMs
			return null
		}
		val thresholdMs = task.stallThreshold.inWholeMilliseconds
		if (nowMs - lastChangedAtMs >= thresholdMs)
		{
			lastChangedAtMs = nowMs    // one fire per stall episode
			return WatchdogFinding(WatchdogKind.STALL, "task=${task.id.raw} unchanged for ${thresholdMs}ms")
		}
		return null
	}
}
