package net.vital.plugins.buildcore.core.services

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceCallStart
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class WithServiceCallTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()

	@BeforeEach
	fun reset()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
	}

	@Test
	fun `success path emits Start then End with SUCCESS outcome and matching callId`()
	{
		val result = withServiceCall(bus, { sid }, "X", "m") { true }
		assertTrue(result)
		assertEquals(2, captured.size)
		val start = captured[0] as ServiceCallStart
		val end   = captured[1] as ServiceCallEnd
		assertEquals(start.callId, end.callId)
		assertEquals(ServiceOutcome.SUCCESS, end.outcome)
		assertTrue(end.durationMillis >= 0L)
	}

	@Test
	fun `block returning false yields FAILURE outcome`()
	{
		withServiceCall(bus, { sid }, "X", "m") { false }
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `block returning null yields FAILURE outcome`()
	{
		withServiceCall<String?>(bus, { sid }, "X", "m") { null }
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `RestrictionViolation classifies as RESTRICTED and rethrows`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.BANK_DISABLED))
		assertThrows(RestrictionViolation::class.java) {
			withServiceCall(bus, { sid }, "X", "m", restriction = OperationalRestriction.BANK_DISABLED) { true }
		}
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `arbitrary exception classifies as EXCEPTION and rethrows`()
	{
		assertThrows(IllegalStateException::class.java) {
			withServiceCall<Boolean>(bus, { sid }, "X", "m") { error("boom") }
		}
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `callId is monotonic`()
	{
		withServiceCall(bus, { sid }, "X", "m") { true }
		withServiceCall(bus, { sid }, "X", "m") { true }
		val first  = (captured[1] as ServiceCallEnd).callId
		val second = (captured[3] as ServiceCallEnd).callId
		assertEquals(first + 1L, second)
	}
}
