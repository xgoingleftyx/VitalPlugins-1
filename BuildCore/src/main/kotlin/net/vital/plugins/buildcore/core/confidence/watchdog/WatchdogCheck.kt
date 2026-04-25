package net.vital.plugins.buildcore.core.confidence.watchdog

import net.vital.plugins.buildcore.core.events.WatchdogKind

/**
 * One periodic check. Returns a finding if the check trips this tick, else null.
 * Plan 6a spec §6.2.
 */
interface WatchdogCheck
{
	fun tick(nowMs: Long): WatchdogFinding?
}

data class WatchdogFinding(val kind: WatchdogKind, val detail: String)
