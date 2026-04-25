package net.vital.plugins.buildcore.core.confidence

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires [ConfidenceTracker] fields and registers [ConfidenceSubscriber].
 * Called once from [net.vital.plugins.buildcore.BuildCorePlugin.startUp]
 * after `ServiceBootstrap.install`. Plan 6a spec §8.1.
 */
object ConfidenceBootstrap
{
	private val installed = AtomicBoolean(false)

	fun install(
		bus: EventBus,
		sessionIdProvider: () -> UUID,
		taskProvider: () -> Pair<Task?, TaskContext?> = { null to null },
		clock: Clock = Clock.systemUTC()
	)
	{
		if (!installed.compareAndSet(false, true)) return
		ConfidenceTracker.bus = bus
		ConfidenceTracker.sessionIdProvider = sessionIdProvider
		ConfidenceTracker.taskProvider = taskProvider
		ConfidenceTracker.clock = clock
		// ConfidenceSubscriber is registered via SubscriberRegistry by the plugin (see BuildCorePlugin).
	}

	internal fun resetForTests()
	{
		installed.set(false)
		ConfidenceTracker.resetForTests()
	}
}
