package net.vital.plugins.buildcore.core.task

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.restrictions.Archetype
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunnerStateMachineTest {

	private val main = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)

	@Test
	fun `TaskInstance starts in PENDING`() {
		val instance = TaskInstance(task = NoOpTask(), criticality = Criticality.OPTIONAL)
		assertEquals(TaskState.PENDING, instance.state)
	}

	@Test
	fun `canTransition rejects skipping to COMPLETED from PENDING`() {
		val instance = TaskInstance(task = NoOpTask(), criticality = Criticality.OPTIONAL)
		assertEquals(false, instance.canTransition(TaskState.COMPLETED))
		assertEquals(true, instance.canTransition(TaskState.VALIDATE))
	}

	@Test
	fun `terminal states reject all transitions`() {
		val instance = TaskInstance(task = NoOpTask(), criticality = Criticality.OPTIONAL)
		instance.setState(TaskState.COMPLETED)
		TaskState.entries.forEach { assertEquals(false, instance.canTransition(it), "from COMPLETED to $it") }

		instance.setState(TaskState.FAILED)
		TaskState.entries.forEach { assertEquals(false, instance.canTransition(it), "from FAILED to $it") }
	}

	@Test
	fun `NoOpTask runs through full lifecycle and reaches COMPLETED`() = runTest(UnconfinedTestDispatcher()) {
		val bus = EventBus()
		val instance = TaskInstance(task = NoOpTask(tickBudget = 2), criticality = Criticality.OPTIONAL)
		val runner = Runner(bus)

		val finalState = runner.run(instance, main)

		assertEquals(TaskState.COMPLETED, finalState)
		assertTrue(instance.state.isTerminal)
	}

	@Test
	fun `failing task reaches FAILED terminal state`() = runTest(UnconfinedTestDispatcher()) {
		val failingTask = object : Task {
			override val id = TaskId("failing")
			override val displayName = "failing"
			override val version = SemVer(0, 0, 1)
			override val moduleId = ModuleId("test")
			override val config = ConfigSchema.EMPTY
			override val methods = listOf(NoOpTask().methods[0])
			override fun validate(ctx: TaskContext) = ValidationResult.Pass
			override fun onStart(ctx: TaskContext) {}
			override fun step(ctx: TaskContext): StepResult =
				StepResult.Fail(FailureReason.Custom("synthetic", "test"), recoverable = false)
			override fun isComplete(ctx: TaskContext) = false
			override fun safeStop(ctx: TaskContext) {}
			override fun progressSignal(ctx: TaskContext) = ProgressFingerprint.EMPTY
			override fun canStopNow(ctx: TaskContext) = true
		}

		val bus = EventBus()
		val instance = TaskInstance(
			task = failingTask,
			criticality = Criticality.OPTIONAL,
			retryPolicy = RetryPolicy(maxAttempts = 1)
		)
		val runner = Runner(bus)

		val finalState = runner.run(instance, main)
		assertEquals(TaskState.FAILED, finalState)
	}
}
