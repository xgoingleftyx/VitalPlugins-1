package net.vital.plugins.buildcore.core.services.walker

import io.mockk.coEvery
import io.mockk.coJustRun
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
import net.vital.plugins.buildcore.core.services.RestrictionGate
import net.vital.plugins.buildcore.core.services.ServiceCallContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import vital.api.entities.Tile
import java.util.UUID

class WalkerServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<WalkerBackend>()
	private val tile = mockk<Tile>(relaxed = true)

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		WalkerService.backend = fakeBackend
		WalkerService.bus = bus
		WalkerService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { WalkerService.resetForTests() }

	@Test
	fun `walkTo success path`() = runTest {
		coEvery { fakeBackend.walkTo(tile) } returns true
		assertTrue(WalkerService.walkTo(tile))
		coVerify(exactly = 1) { fakeBackend.walkTo(tile) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `walkTo failure path`() = runTest {
		coEvery { fakeBackend.walkTo(tile) } returns false
		assertFalse(WalkerService.walkTo(tile))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `walkTo exception path`()
	{
		coEvery { fakeBackend.walkTo(tile) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { WalkerService.walkTo(tile) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `walkExact success path`() = runTest {
		coEvery { fakeBackend.walkExact(tile) } returns true
		assertTrue(WalkerService.walkExact(tile))
		coVerify(exactly = 1) { fakeBackend.walkExact(tile) }
	}

	@Test
	fun `walkExact failure path`() = runTest {
		coEvery { fakeBackend.walkExact(tile) } returns false
		assertFalse(WalkerService.walkExact(tile))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `walkExact exception path`()
	{
		coEvery { fakeBackend.walkExact(tile) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { WalkerService.walkExact(tile) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `stop emits Start and End with SUCCESS outcome`() = runTest {
		coJustRun { fakeBackend.stop() }
		WalkerService.stop()
		coVerify(exactly = 1) { fakeBackend.stop() }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `stop exception path`()
	{
		coEvery { fakeBackend.stop() } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { WalkerService.stop() } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}
}
