package net.vital.plugins.buildcore.core.services.equipment

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

class EquipmentServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<EquipmentBackend>()

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		EquipmentService.backend = fakeBackend
		EquipmentService.bus = bus
		EquipmentService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { EquipmentService.resetForTests() }

	@Test
	fun `equip success path`() = runTest {
		coEvery { fakeBackend.equip(1163) } returns true
		assertTrue(EquipmentService.equip(1163))
		coVerify(exactly = 1) { fakeBackend.equip(1163) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `equip failure path`() = runTest {
		coEvery { fakeBackend.equip(1163) } returns false
		assertFalse(EquipmentService.equip(1163))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `equip exception path`()
	{
		coEvery { fakeBackend.equip(1163) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { EquipmentService.equip(1163) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `unequip success path`() = runTest {
		coEvery { fakeBackend.unequip(0) } returns true
		assertTrue(EquipmentService.unequip(0))
		coVerify(exactly = 1) { fakeBackend.unequip(0) }
	}

	@Test
	fun `unequip failure path`() = runTest {
		coEvery { fakeBackend.unequip(0) } returns false
		assertFalse(EquipmentService.unequip(0))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `unequip exception path`()
	{
		coEvery { fakeBackend.unequip(0) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { EquipmentService.unequip(0) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}
}
