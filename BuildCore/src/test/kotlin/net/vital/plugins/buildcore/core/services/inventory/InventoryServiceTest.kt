package net.vital.plugins.buildcore.core.services.inventory

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
import net.vital.plugins.buildcore.core.services.RestrictionGate
import net.vital.plugins.buildcore.core.services.ServiceCallContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InventoryServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<InventoryBackend>()

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		InventoryService.backend = fakeBackend
		InventoryService.bus = bus
		InventoryService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { InventoryService.resetForTests() }

	@Test
	fun `drop success path`() = runTest {
		coEvery { fakeBackend.drop(995) } returns true
		assertTrue(InventoryService.drop(995))
		coVerify(exactly = 1) { fakeBackend.drop(995) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `drop failure path`() = runTest {
		coEvery { fakeBackend.drop(995) } returns false
		assertFalse(InventoryService.drop(995))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `drop exception path`()
	{
		coEvery { fakeBackend.drop(995) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { InventoryService.drop(995) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `useOn success path`() = runTest {
		coEvery { fakeBackend.useOn(0, 1) } returns true
		assertTrue(InventoryService.useOn(0, 1))
		coVerify(exactly = 1) { fakeBackend.useOn(0, 1) }
	}

	@Test
	fun `useOn failure path`() = runTest {
		coEvery { fakeBackend.useOn(0, 1) } returns false
		assertFalse(InventoryService.useOn(0, 1))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `useOn exception path`()
	{
		coEvery { fakeBackend.useOn(0, 1) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { InventoryService.useOn(0, 1) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `interact success path`() = runTest {
		coEvery { fakeBackend.interact(3, "Use") } returns true
		assertTrue(InventoryService.interact(3, "Use"))
		coVerify(exactly = 1) { fakeBackend.interact(3, "Use") }
	}

	@Test
	fun `interact failure path`() = runTest {
		coEvery { fakeBackend.interact(3, "Use") } returns false
		assertFalse(InventoryService.interact(3, "Use"))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `interact exception path`()
	{
		coEvery { fakeBackend.interact(3, "Use") } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { InventoryService.interact(3, "Use") } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}
}
