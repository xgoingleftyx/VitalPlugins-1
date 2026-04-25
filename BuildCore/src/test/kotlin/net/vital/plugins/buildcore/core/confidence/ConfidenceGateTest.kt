package net.vital.plugins.buildcore.core.confidence

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.events.EventBus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ConfidenceGateTest
{
	private var nowMs = 1_000_000L
	private val clock = object : Clock()
	{
		override fun getZone() = ZoneOffset.UTC
		override fun withZone(z: java.time.ZoneId) = this
		override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
		override fun millis(): Long = nowMs
	}
	private val gsp = FakeGameStateProvider()

	@BeforeEach
	fun reset()
	{
		ConfidenceTracker.resetForTests()
		ConfidenceTracker.bus = mockk<EventBus>().also { every { it.tryEmit(any()) } returns true }
		ConfidenceTracker.sessionIdProvider = { UUID.randomUUID() }
		ConfidenceTracker.clock = clock
		ConfidenceTracker.gameStateProvider = gsp
	}

	@Test
	fun `READ_ONLY is pass-through`()
	{
		gsp.hp = 0.0   // worst-case state
		ConfidenceGate.check(ActionStakes.READ_ONLY)   // must not throw
	}

	@Test
	fun `LOW passes when score above threshold`()
	{
		ConfidenceGate.check(ActionStakes.LOW)   // default state → score ~1.0
	}

	@Test
	fun `MEDIUM throws when score below threshold`()
	{
		gsp.hp = 0.2   // pulls score down
		gsp.dialogVisible = true
		gsp.widgetId = 999
		val ex = assertThrows(ConfidenceTooLow::class.java) {
			ConfidenceGate.check(ActionStakes.HIGH)
		}
		assertTrue(ex.current < 0.8)
		assertEquals(0.8, ex.required)
		assertNotNull(ex.worstSignal)
	}

	@Test
	fun `worstSignal reflects min of perSignal`()
	{
		gsp.hp = 0.2
		try
		{
			ConfidenceGate.check(ActionStakes.CRITICAL)
			fail("expected ConfidenceTooLow")
		}
		catch (e: ConfidenceTooLow)
		{
			assertEquals("HP_NORMAL", e.worstSignal)
		}
	}
}
