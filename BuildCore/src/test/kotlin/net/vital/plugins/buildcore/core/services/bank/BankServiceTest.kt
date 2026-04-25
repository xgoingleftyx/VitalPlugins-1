package net.vital.plugins.buildcore.core.services.bank

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

class BankServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<BankBackend>()

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		BankService.backend = fakeBackend
		BankService.bus = bus
		BankService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { BankService.resetForTests() }

	@Test
	fun `open success path`() = runTest {
		coEvery { fakeBackend.open() } returns true
		assertTrue(BankService.open())
		coVerify(exactly = 1) { fakeBackend.open() }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `open failure path`() = runTest {
		coEvery { fakeBackend.open() } returns false
		assertFalse(BankService.open())
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `open exception path`()
	{
		coEvery { fakeBackend.open() } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { BankService.open() } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `open restricted path`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.BANK_DISABLED))
		assertThrows(RestrictionViolation::class.java) { runBlocking { BankService.open() } }
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
		coVerify(exactly = 0) { fakeBackend.open() }
	}

	@Test
	fun `deposit forwards itemId and amount`() = runTest {
		coEvery { fakeBackend.deposit(995, 100) } returns true
		assertTrue(BankService.deposit(995, 100))
		coVerify(exactly = 1) { fakeBackend.deposit(995, 100) }
	}

	@Test
	fun `withdraw forwards itemId and amount`() = runTest {
		coEvery { fakeBackend.withdraw(995, 50) } returns true
		assertTrue(BankService.withdraw(995, 50))
		coVerify(exactly = 1) { fakeBackend.withdraw(995, 50) }
	}

	@Test
	fun `depositAll happy path`() = runTest {
		coEvery { fakeBackend.depositAll() } returns true
		assertTrue(BankService.depositAll())
	}

	@Test
	fun `depositInventory happy path`() = runTest {
		coEvery { fakeBackend.depositInventory() } returns true
		assertTrue(BankService.depositInventory())
	}

	@Test
	fun `close happy path`() = runTest {
		coEvery { fakeBackend.close() } returns true
		assertTrue(BankService.close())
	}
}
