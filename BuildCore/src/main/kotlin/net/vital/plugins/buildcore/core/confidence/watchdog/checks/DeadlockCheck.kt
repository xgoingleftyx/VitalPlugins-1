package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogFinding
import net.vital.plugins.buildcore.core.events.WatchdogKind

class DeadlockCheck(
	private val heartbeatProvider: () -> Long?,
	private val toleranceMs: Long = 15_000L
) : WatchdogCheck
{
	private var lastFiredAtMs: Long = Long.MIN_VALUE

	override fun tick(nowMs: Long): WatchdogFinding?
	{
		val hb = heartbeatProvider() ?: return null
		val sinceHeartbeat = nowMs - hb
		if (sinceHeartbeat <= toleranceMs)
		{
			lastFiredAtMs = Long.MIN_VALUE    // re-arm
			return null
		}
		if (lastFiredAtMs == Long.MIN_VALUE)
		{
			lastFiredAtMs = nowMs
			return WatchdogFinding(WatchdogKind.DEADLOCK, "no heartbeat for ${sinceHeartbeat}ms")
		}
		return null
	}
}
