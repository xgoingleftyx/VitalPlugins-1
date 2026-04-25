package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.events.WatchdogKind
import net.vital.plugins.buildcore.core.task.ProgressFingerprint
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import net.vital.plugins.buildcore.core.task.TaskId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

class StallCheckTest
{
	@Test
	fun `fires when fingerprint unchanged for stallThreshold`()
	{
		val task = mockk<Task>()
		every { task.id } returns TaskId("foo")
		every { task.stallThreshold } returns 5.minutes
		val ctx = mockk<TaskContext>(relaxed = true)
		val fp = mockk<ProgressFingerprint>()
		every { task.progressSignal(ctx) } returns fp

		val check = StallCheck { task to ctx }

		assertNull(check.tick(0L))                 // initial sample
		assertNull(check.tick(60_000L))            // 1min
		assertNull(check.tick(299_000L))           // just under threshold
		val finding = check.tick(300_000L)         // = threshold
		assertNotNull(finding)
		assertEquals(WatchdogKind.STALL, finding!!.kind)
	}

	@Test
	fun `resets when fingerprint changes`()
	{
		val task = mockk<Task>()
		every { task.id } returns TaskId("foo")
		every { task.stallThreshold } returns 5.minutes
		val ctx = mockk<TaskContext>(relaxed = true)
		val fp1 = mockk<ProgressFingerprint>()
		val fp2 = mockk<ProgressFingerprint>()
		every { task.progressSignal(ctx) } returnsMany listOf(fp1, fp1, fp2, fp2)

		val check = StallCheck { task to ctx }
		check.tick(0L)
		check.tick(200_000L)
		check.tick(250_000L)                       // fp changed -> resets
		assertNull(check.tick(400_000L))           // only 150_000 since reset
	}

	@Test
	fun `null task returns null`()
	{
		val check = StallCheck { null to null }
		assertNull(check.tick(System.currentTimeMillis()))
	}
}
