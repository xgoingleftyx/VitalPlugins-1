package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.confidence.ConfidenceTracker
import net.vital.plugins.buildcore.core.confidence.FakeGameStateProvider
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.WatchdogKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class UncertainCheckTest
{
	private val gsp = FakeGameStateProvider()

	@BeforeEach
	fun reset()
	{
		ConfidenceTracker.resetForTests()
		ConfidenceTracker.bus = mockk<EventBus>().also { every { it.tryEmit(any()) } returns true }
		ConfidenceTracker.sessionIdProvider = { UUID.randomUUID() }
		ConfidenceTracker.clock = Clock.systemUTC()
		ConfidenceTracker.gameStateProvider = gsp
	}

	@Test
	fun `low score sustained 30s fires`()
	{
		// Pull confidence well below 0.4 threshold: HP critical + dialog + unknown widget + no position/entities
		gsp.hp = 0.0; gsp.dialogVisible = true; gsp.widgetId = 999
		// Use threshold=0.9 so the degraded score (~0.725) is reliably below threshold
		val check = UncertainCheck(threshold = 0.9)
		assertNull(check.tick(0L))
		assertNull(check.tick(20_000L))
		val finding = check.tick(35_000L)
		assertNotNull(finding)
		assertEquals(WatchdogKind.UNCERTAIN, finding!!.kind)
	}

	@Test
	fun `recovery before 30s clears`()
	{
		gsp.hp = 0.0; gsp.dialogVisible = true; gsp.widgetId = 999
		val check = UncertainCheck(threshold = 0.9)
		check.tick(0L)
		gsp.hp = 1.0; gsp.dialogVisible = false; gsp.widgetId = null
		ConfidenceTracker.resetForTests()       // force recompute
		ConfidenceTracker.bus = mockk<EventBus>().also { every { it.tryEmit(any()) } returns true }
		ConfidenceTracker.sessionIdProvider = { UUID.randomUUID() }
		ConfidenceTracker.gameStateProvider = gsp
		assertNull(check.tick(20_000L))         // recovered
	}

	@Test
	fun `null confidence tracker bus still runs check`()
	{
		// UncertainCheck with null bus should not crash - ConfidenceTracker handles null bus gracefully
		ConfidenceTracker.bus = null
		gsp.hp = 1.0; gsp.dialogVisible = false; gsp.widgetId = null
		val check = UncertainCheck()
		assertNull(check.tick(0L))   // score is high -> no finding
	}
}
