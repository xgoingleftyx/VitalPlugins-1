package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.restrictions.Archetype
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SafeStopContractTest {

	private val main = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)

	private fun stubTask(canStopNowResults: List<Boolean>, safeStopRunsCounter: IntArray = intArrayOf(0)): Task {
		val iter = canStopNowResults.iterator()
		var last: Boolean = canStopNowResults.lastOrNull() ?: true
		return object : Task {
			override val id = TaskId("stub")
			override val displayName = "stub"
			override val version = SemVer(0, 0, 1)
			override val moduleId = ModuleId("test")
			override val config = ConfigSchema.EMPTY
			override val methods = listOf(stubMethod())
			override fun validate(ctx: TaskContext) = ValidationResult.Pass
			override fun onStart(ctx: TaskContext) {}
			override fun step(ctx: TaskContext) = StepResult.Continue()
			override fun isComplete(ctx: TaskContext) = false
			override fun safeStop(ctx: TaskContext) { safeStopRunsCounter[0]++ }
			override fun progressSignal(ctx: TaskContext) = ProgressFingerprint.EMPTY
			override fun canStopNow(ctx: TaskContext): Boolean {
				if (iter.hasNext()) {
					last = iter.next()
				}
				return last
			}
		}
	}

	private fun stubMethod(): Method = object : Method {
		override val id = MethodId("stub.m")
		override val displayName = "stub.m"
		override val description = "stub.m"
		override val paths = listOf(ExecutionPath(PathId("stub.m.iron"), PathKind.IRONMAN))
		override val requirements: Requirement? = null
		override val effects: Set<Effect> = emptySet()
		override val config = ConfigSchema.EMPTY
		override val locationFootprint: Set<AreaTag> = emptySet()
		override val risk = RiskProfile.NONE
		override fun estimatedRate(accountState: AccountState) = XpPerHour.ZERO
	}

	@Test
	fun `safe-stop waits for canStopNow to return true`() = runTest(UnconfinedTestDispatcher()) {
		val ctr = intArrayOf(0)
		val task = stubTask(canStopNowResults = listOf(false, false, true), safeStopRunsCounter = ctr)
		val instance = TaskInstance(task = task, criticality = Criticality.OPTIONAL)
		instance.setState(TaskState.RUNNING)
		val bus = EventBus()
		val ctx = SimpleTaskContext(restrictions = main, eventBus = bus)

		val result = SafeStopContract.perform(
			instance = instance,
			ctx = ctx,
			pollInterval = 1.milliseconds,
			maxDefer = 1000.milliseconds
		)

		assertTrue(result.completed)
		assertEquals(1, ctr[0]) // safeStop() invoked once
	}

	@Test
	fun `safe-stop gives up after maxDefer and still runs safeStop`() = runTest(UnconfinedTestDispatcher()) {
		val ctr = intArrayOf(0)
		// Returns false forever
		val task = stubTask(canStopNowResults = List(100) { false }, safeStopRunsCounter = ctr)
		val instance = TaskInstance(task = task, criticality = Criticality.OPTIONAL)
		instance.setState(TaskState.RUNNING)
		val bus = EventBus()
		val ctx = SimpleTaskContext(restrictions = main, eventBus = bus)

		val result = SafeStopContract.perform(
			instance = instance,
			ctx = ctx,
			pollInterval = 1.milliseconds,
			maxDefer = 10.milliseconds
		)

		assertEquals(false, result.canStopNowReachedTrue)
		assertTrue(result.completed)
		assertEquals(1, ctr[0])
	}
}
