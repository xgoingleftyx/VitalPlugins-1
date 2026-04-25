package net.vital.plugins.buildcore.core.services

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.confidence.ActionStakes
import net.vital.plugins.buildcore.core.confidence.ConfidenceTooLow
import net.vital.plugins.buildcore.core.confidence.ConfidenceTracker
import net.vital.plugins.buildcore.core.confidence.FakeGameStateProvider
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.ConfidenceUnderconfidentAction
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class WithServiceCallStakesTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also { every { it.tryEmit(any()) } answers { captured.add(firstArg()); true } }
	private val sid = UUID.randomUUID()
	private val gsp = FakeGameStateProvider()

	@BeforeEach
	fun reset()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		ConfidenceTracker.resetForTests()
		ConfidenceTracker.bus = mockk<EventBus>().also { every { it.tryEmit(any()) } returns true }
		ConfidenceTracker.sessionIdProvider = { sid }
		ConfidenceTracker.clock = Clock.systemUTC()
		ConfidenceTracker.gameStateProvider = gsp
	}

	@Test
	fun `READ_ONLY skips confidence check entirely`()
	{
		gsp.hp = 0.0   // worst case
		val r = withServiceCall(bus, { sid }, "X", "m", stakes = ActionStakes.READ_ONLY) { true }
		assertTrue(r)
		assertEquals(ServiceOutcome.SUCCESS, (captured.last() as ServiceCallEnd).outcome)
	}

	@Test
	fun `under-threshold throws ConfidenceTooLow and classifies UNCONFIDENT`()
	{
		gsp.hp = 0.2
		gsp.dialogVisible = true
		gsp.widgetId = 999
		var blockInvoked = false
		assertThrows(ConfidenceTooLow::class.java) {
			withServiceCall(bus, { sid }, "X", "m", stakes = ActionStakes.HIGH) { blockInvoked = true; true }
		}
		assertFalse(blockInvoked)
		val end = captured.filterIsInstance<ServiceCallEnd>().single()
		assertEquals(ServiceOutcome.UNCONFIDENT, end.outcome)
	}

	@Test
	fun `under-threshold emits ConfidenceUnderconfidentAction`()
	{
		gsp.hp = 0.2
		gsp.dialogVisible = true
		gsp.widgetId = 999
		try
		{
			withServiceCall(bus, { sid }, "BankService", "open", stakes = ActionStakes.HIGH) { true }
		}
		catch (_: ConfidenceTooLow) {}
		val ev = captured.filterIsInstance<ConfidenceUnderconfidentAction>().single()
		assertEquals("BankService", ev.serviceName)
		assertEquals("open", ev.methodName)
		assertEquals(0.8, ev.required)
	}

	@Test
	fun `restriction check happens before confidence check`()
	{
		gsp.hp = 0.0   // would also fail confidence
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.BANK_DISABLED))
		assertThrows(RestrictionViolation::class.java) {
			withServiceCall(bus, { sid }, "X", "m",
				restriction = OperationalRestriction.BANK_DISABLED,
				stakes = ActionStakes.HIGH) { true }
		}
		// outcome must be RESTRICTED, not UNCONFIDENT
		assertEquals(ServiceOutcome.RESTRICTED, (captured.last() as ServiceCallEnd).outcome)
	}
}
