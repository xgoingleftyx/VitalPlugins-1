package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogFinding
import net.vital.plugins.buildcore.core.events.WatchdogKind

class LeakCheck(
	private val maxScopeMs: Long = 30_000L
) : WatchdogCheck
{
	private var lastFiredForEnteredMs: Long? = null

	override fun tick(nowMs: Long): WatchdogFinding?
	{
		val entered = PrecisionGate.scopeEnteredAtMs ?: run { lastFiredForEnteredMs = null; return null }
		val held = nowMs - entered
		if (held <= maxScopeMs)
		{
			lastFiredForEnteredMs = null
			return null
		}
		if (lastFiredForEnteredMs == entered) return null    // already fired for this scope episode
		lastFiredForEnteredMs = entered
		return WatchdogFinding(WatchdogKind.LEAK, "precision held ${held}ms")
	}
}
