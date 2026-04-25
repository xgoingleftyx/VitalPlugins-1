package net.vital.plugins.buildcore.core.antiban.breaks

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.EarlyStopReason
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import org.junit.jupiter.api.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BedtimeEscalatorTest
{
	@Test
	fun `escalate calls requestEarlyStop exactly once`() = runTest {
		val bus = mockk<EventBus>(relaxed = true)
		val task = mockk<Task>(relaxed = true)
		val ctx  = mockk<TaskContext>(relaxed = true)
		val cfg = BreakConfig.defaults().tiers[net.vital.plugins.buildcore.core.events.BreakTier.BEDTIME]!!
		val escalator = BedtimeEscalator(bus, { UUID.randomUUID() })
		escalator.escalate(task, ctx, cfg)
		coVerify(exactly = 1) { task.requestEarlyStop(EarlyStopReason.BEDTIME) }
	}

	@Test
	fun `escalate is a no-op when task is null`() = runTest {
		val bus = mockk<EventBus>(relaxed = true)
		val cfg = BreakConfig.defaults().tiers[net.vital.plugins.buildcore.core.events.BreakTier.BEDTIME]!!
		val escalator = BedtimeEscalator(bus, { UUID.randomUUID() })
		escalator.escalate(null, null, cfg)   // must not throw
	}
}
