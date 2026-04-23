package net.vital.plugins.buildcore.core.task

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.TaskCompleted
import net.vital.plugins.buildcore.core.events.TaskStarted
import net.vital.plugins.buildcore.core.events.TaskValidated
import net.vital.plugins.buildcore.core.restrictions.Archetype
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoOpTaskRunTest {

	private val main = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)

	@Test
	fun `NoOpTask emits validated started completed events in order`() = runTest(UnconfinedTestDispatcher()) {
		val bus = EventBus()
		val received = mutableListOf<BusEvent>()
		// NoOpTask(tickBudget=1) emits exactly 5 events:
		// Validated, MethodPicked, PathPicked, Started, Completed
		val subscription = launch {
			bus.events.take(5).toList(received)
		}

		val instance = TaskInstance(task = NoOpTask(tickBudget = 1), criticality = Criticality.OPTIONAL)
		val runner = Runner(bus)

		runner.run(instance, main)
		subscription.join()

		val types = received.map { it::class.simpleName }
		assertTrue("TaskValidated" in types, "missing TaskValidated in $types")
		assertTrue("TaskStarted" in types, "missing TaskStarted in $types")
		assertTrue("TaskCompleted" in types, "missing TaskCompleted in $types")

		// Order: Validated must come before Started, Started before Completed.
		val validatedIdx = received.indexOfFirst { it is TaskValidated }
		val startedIdx = received.indexOfFirst { it is TaskStarted }
		val completedIdx = received.indexOfFirst { it is TaskCompleted }
		assertTrue(validatedIdx < startedIdx, "validated=$validatedIdx started=$startedIdx")
		assertTrue(startedIdx < completedIdx, "started=$startedIdx completed=$completedIdx")
	}

	@Test
	fun `NoOpTask terminates in COMPLETED with one method pick per attempt`() = runTest(UnconfinedTestDispatcher()) {
		val bus = EventBus()
		val instance = TaskInstance(task = NoOpTask(tickBudget = 5), criticality = Criticality.OPTIONAL)
		val runner = Runner(bus)

		val finalState = runner.run(instance, main)

		assertEquals(TaskState.COMPLETED, finalState)
	}
}
