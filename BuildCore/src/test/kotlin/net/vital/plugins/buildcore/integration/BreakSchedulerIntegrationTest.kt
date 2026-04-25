package net.vital.plugins.buildcore.integration

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.antiban.AntibanBootstrap
import net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate
import net.vital.plugins.buildcore.core.antiban.precision.PrecisionWindow
import net.vital.plugins.buildcore.core.events.*
import net.vital.plugins.buildcore.core.logging.LogDirLayout
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BreakSchedulerIntegrationTest
{
	@AfterEach fun teardown() { AntibanBootstrap.resetForTests() }

	@Test
	fun `withSurvival inside an active break preempts it`(@TempDir root: Path) = runTest {
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }
		val sid = UUID.randomUUID()
		val layout = LogDirLayout(root.resolve("logs"))

		AntibanBootstrap.install(bus, { sid }, layout)
		val scheduler = AntibanBootstrap.breakScheduler!!
		val task = mockk<Task>(relaxed = true)
		val ctx  = mockk<TaskContext>(relaxed = true)
		every { task.canStopNow(any()) } returns true
		AntibanBootstrap.setActiveTask(task, ctx)

		val job = launch { scheduler.run() }
		advanceTimeBy(60_000L)   // let some interval pass; MICRO interval default 3-12min so may not fire — instead manually start
		// Push an active break for the assertion:
		scheduler.activeStateForTests().startActive(BreakTier.MICRO, plannedDurationMs = 30_000L, startedAtMs = 0L)

		PrecisionWindow.withSurvival {
			// Simulate a primitive call — PrecisionGate.enter(SURVIVAL) triggers the preempt hook.
			PrecisionGate.enter(net.vital.plugins.buildcore.core.events.InputMode.SURVIVAL)
		}

		assertNull(scheduler.activeStateForTests().activeOrNull())
		assertTrue(emitted.any { it is BreakPreempted && it.tier == BreakTier.MICRO })

		job.cancelAndJoin()
	}
}
