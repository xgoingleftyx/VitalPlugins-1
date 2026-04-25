package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.confidence.ConfidenceTracker
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogFinding
import net.vital.plugins.buildcore.core.events.WatchdogKind

class UncertainCheck(
	private val threshold: Double = 0.4,
	private val sustainedMs: Long = 30_000L
) : WatchdogCheck
{
	private var firstBelowMs: Long? = null

	override fun tick(nowMs: Long): WatchdogFinding?
	{
		val score = ConfidenceTracker.current().score
		if (score < threshold)
		{
			val first = firstBelowMs
			if (first == null) { firstBelowMs = nowMs; return null }
			if (nowMs - first >= sustainedMs)
			{
				firstBelowMs = nowMs    // re-arm after fire
				return WatchdogFinding(WatchdogKind.UNCERTAIN, "score=$score sustained ${sustainedMs}ms")
			}
			return null
		}
		else
		{
			firstBelowMs = null
			return null
		}
	}
}
