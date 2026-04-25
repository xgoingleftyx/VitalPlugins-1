package net.vital.plugins.buildcore.core.antiban.breaks

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.events.*
import net.vital.plugins.buildcore.core.task.Task
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class BreakSchedulerPreemptionTest
{
	@Test
	fun `preempt clears active break and emits BreakPreempted`()
	{
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }
		val task = mockk<Task>(relaxed = true)
		val cfg = BreakConfig.defaults()
		val sched = BreakScheduler(bus, { UUID.randomUUID() }, { task }, cfg, JavaUtilRng(seed = 1L))

		// Manually push an active break into BreakState for this unit-level assertion.
		val state = sched.activeStateForTests()
		state.startActive(BreakTier.MICRO, plannedDurationMs = 30_000L, startedAtMs = 0L)
		assertNotNull(state.activeOrNull())

		sched.preempt()

		assertNull(state.activeOrNull())
		assertTrue(emitted.any { it is BreakPreempted && it.tier == BreakTier.MICRO })
	}

	@Test
	fun `preempt is idempotent when no break active`()
	{
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }
		val sched = BreakScheduler(bus, { UUID.randomUUID() }, { mockk(relaxed = true) }, BreakConfig.defaults(), JavaUtilRng(seed = 1L))

		sched.preempt()
		sched.preempt()

		assertTrue(emitted.none { it is BreakPreempted })
	}
}
