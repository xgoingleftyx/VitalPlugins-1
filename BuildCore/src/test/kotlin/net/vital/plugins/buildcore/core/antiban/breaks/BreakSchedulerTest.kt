package net.vital.plugins.buildcore.core.antiban.breaks

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.events.*
import net.vital.plugins.buildcore.core.task.Task
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class BreakSchedulerTest
{
	private val sid = UUID.randomUUID()

	@Test
	fun `scheduler fires break when canStopNow returns true`() = runTest {
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }

		val canStop = AtomicBoolean(true)
		val task = mockk<Task>()
		every { task.canStopNow(any()) } answers { canStop.get() }

		val cfg = BreakConfig(tiers = mapOf(
			BreakTier.MICRO to TierConfig(true, 1_000L..1_000L, 5_000L..5_000L, 1_000L, DeferAction.DROP)
		))
		val clock = testScheduler
		val sched = BreakScheduler(bus, { sid }, { task }, cfg, JavaUtilRng(seed = 1L), nowMs = { clock.currentTime })
		val job = launch { sched.run() }

		advanceTimeBy(5_000L)   // first interval
		advanceTimeBy(2_000L)   // executeBreak: 1s plan + slack
		job.cancelAndJoin()

		assertTrue(emitted.any { it is BreakStarted && it.tier == BreakTier.MICRO })
		assertTrue(emitted.any { it is BreakEnded   && it.tier == BreakTier.MICRO })
	}

	@Test
	fun `scheduler defers when canStopNow false then drops on timeout`() = runTest {
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }

		val task = mockk<Task>()
		every { task.canStopNow(any()) } returns false

		val cfg = BreakConfig(tiers = mapOf(
			BreakTier.MICRO to TierConfig(true, 1_000L..1_000L, 5_000L..5_000L, 2_000L, DeferAction.DROP)
		))
		val clock = testScheduler
		val sched = BreakScheduler(bus, { sid }, { task }, cfg, JavaUtilRng(seed = 1L), nowMs = { clock.currentTime })
		val job = launch { sched.run() }

		advanceTimeBy(5_000L)   // first scheduled fire
		advanceTimeBy(3_000L)   // exceeds maxDeferMs (2s)
		job.cancelAndJoin()

		assertTrue(emitted.any { it is BreakDeferred })
		assertTrue(emitted.any { it is BreakDropped })
		assertFalse(emitted.any { it is BreakStarted })
	}

	@Test
	fun `RESCHEDULE re-arms next-fire after timeout`() = runTest {
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }

		val task = mockk<Task>()
		every { task.canStopNow(any()) } returns false

		val cfg = BreakConfig(tiers = mapOf(
			BreakTier.MICRO to TierConfig(true, 1_000L..1_000L, 5_000L..5_000L, 1_000L, DeferAction.RESCHEDULE)
		))
		val clock = testScheduler
		val sched = BreakScheduler(bus, { sid }, { task }, cfg, JavaUtilRng(seed = 1L), nowMs = { clock.currentTime })
		val job = launch { sched.run() }

		advanceTimeBy(5_000L); advanceTimeBy(2_000L)   // first fire → defer → reschedule
		advanceTimeBy(2_000L); advanceTimeBy(2_000L)   // re-attempt
		job.cancelAndJoin()

		assertTrue(emitted.count { it is BreakRescheduled } >= 1)
	}
}
