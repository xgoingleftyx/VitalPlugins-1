package net.vital.plugins.buildcore.core.services.world

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceCallStart
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import net.vital.plugins.buildcore.core.services.OperationalRestriction
import net.vital.plugins.buildcore.core.services.RestrictionGate
import net.vital.plugins.buildcore.core.services.RestrictionViolation
import net.vital.plugins.buildcore.core.services.ServiceCallContext
import net.vital.plugins.buildcore.core.services.StaticRestrictionEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class WorldServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<WorldBackend>()

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		WorldService.backend = fakeBackend
		WorldService.bus = bus
		WorldService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { WorldService.resetForTests() }

	@Test
	fun `hop success path`() = runTest {
		coEvery { fakeBackend.hop(301) } returns true
		assertTrue(WorldService.hop(301))
		coVerify(exactly = 1) { fakeBackend.hop(301) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `hop failure path`() = runTest {
		coEvery { fakeBackend.hop(301) } returns false
		assertFalse(WorldService.hop(301))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `hop exception path`()
	{
		coEvery { fakeBackend.hop(301) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { WorldService.hop(301) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `hop restricted path`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.WORLD_HOP_DISABLED))
		assertThrows(RestrictionViolation::class.java) { runBlocking { WorldService.hop(301) } }
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
		coVerify(exactly = 0) { fakeBackend.hop(301) }
	}
}
