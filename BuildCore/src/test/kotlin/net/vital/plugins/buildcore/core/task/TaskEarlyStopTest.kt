package net.vital.plugins.buildcore.core.task

import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.EarlyStopReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class TaskEarlyStopTest
{
	@Test
	fun `default requestEarlyStop is a no-op`() = runTest {
		val task = NoOpTask()
		task.requestEarlyStop(EarlyStopReason.BEDTIME)  // must not throw, must not change state
	}

	@Test
	fun `task can override requestEarlyStop to record reason`() = runTest {
		val captured = AtomicReference<EarlyStopReason?>()
		val task = object : NoOpTask()
		{
			override suspend fun requestEarlyStop(reason: EarlyStopReason)
			{
				captured.set(reason)
			}
		}
		task.requestEarlyStop(EarlyStopReason.USER)
		assertEquals(EarlyStopReason.USER, captured.get())
	}
}
