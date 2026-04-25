package net.vital.plugins.buildcore.core.confidence.watchdog

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.WatchdogTriggered
import java.util.UUID

/**
 * Driver: runs all checks once per [tickIntervalMs] and emits
 * [WatchdogTriggered] events. Plan 6a spec §6.2.
 */
class Watchdog(
	private val bus: EventBus,
	private val sessionIdProvider: () -> UUID,
	private val checks: List<WatchdogCheck>,
	private val tickIntervalMs: Long = 1_000L,
	private val nowMs: () -> Long = System::currentTimeMillis
)
{
	suspend fun run() = coroutineScope {
		while (currentCoroutineContext()[Job]?.isActive != false)
		{
			for (check in checks)
			{
				try
				{
					check.tick(nowMs())?.let { trigger ->
						bus.tryEmit(WatchdogTriggered(
							sessionId = sessionIdProvider(),
							kind = trigger.kind,
							detail = trigger.detail
						))
					}
				}
				catch (_: Throwable) { /* one bad check must not kill the loop */ }
			}
			delay(tickIntervalMs)
		}
	}
}
