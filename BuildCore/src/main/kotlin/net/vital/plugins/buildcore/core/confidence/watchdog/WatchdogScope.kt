package net.vital.plugins.buildcore.core.confidence.watchdog

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Single-thread coroutine scope for the watchdog. Sibling of LoggerScope.
 * Plan 6a spec §6.1.
 */
class WatchdogScope : AutoCloseable
{
	private val dispatcher = Executors.newSingleThreadExecutor { r ->
		Thread(r, "Watchdog").apply { isDaemon = true }
	}.asCoroutineDispatcher()

	private val job = SupervisorJob()
	val coroutineScope: CoroutineScope = CoroutineScope(dispatcher + job + CoroutineName("WatchdogScope"))

	override fun close()
	{
		job.cancel()
		dispatcher.close()
	}
}
