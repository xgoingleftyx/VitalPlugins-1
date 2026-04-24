package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.PerformanceSample
import net.vital.plugins.buildcore.core.events.TaskStarted
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceAggregatorTest {

	@Test
	fun `emits PerformanceSample after interval elapses`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val bus = EventBus()
		val agg = PerformanceAggregator(intervalMillis = 1_000L, dispatcher = dispatcher)

		// collect the first PerformanceSample
		val collected = async { bus.events.filterIsInstance<PerformanceSample>().first() }

		agg.start(bus)
		// tick some events
		val sid = UUID.randomUUID()
		repeat(5) {
			bus.emit(TaskStarted(sessionId = sid, taskInstanceId = UUID.randomUUID(),
				taskId = "t$it", methodId = "m", pathId = "p"))
		}
		advanceTimeBy(1_500L)
		runCurrent()

		val sample = collected.await()
		assertTrue(sample.intervalSeconds >= 1)
		assertTrue(sample.jvmHeapUsedMb > 0)
		agg.stop()
	}
}
