package net.vital.plugins.buildcore.core.services.ge

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

class GrandExchangeServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<GrandExchangeBackend>()

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		GrandExchangeService.backend = fakeBackend
		GrandExchangeService.bus = bus
		GrandExchangeService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { GrandExchangeService.resetForTests() }

	// ── open ──────────────────────────────────────────────────────────────────

	@Test
	fun `open success path`() = runTest {
		coEvery { fakeBackend.open() } returns true
		assertTrue(GrandExchangeService.open())
		coVerify(exactly = 1) { fakeBackend.open() }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `open failure path`() = runTest {
		coEvery { fakeBackend.open() } returns false
		assertFalse(GrandExchangeService.open())
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `open exception path`()
	{
		coEvery { fakeBackend.open() } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { GrandExchangeService.open() } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `open restricted path`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.GRAND_EXCHANGE_DISABLED))
		assertThrows(RestrictionViolation::class.java) { runBlocking { GrandExchangeService.open() } }
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
		coVerify(exactly = 0) { fakeBackend.open() }
	}

	// ── close ─────────────────────────────────────────────────────────────────

	@Test
	fun `close success path`() = runTest {
		coEvery { fakeBackend.close() } returns true
		assertTrue(GrandExchangeService.close())
		coVerify(exactly = 1) { fakeBackend.close() }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `close failure path`() = runTest {
		coEvery { fakeBackend.close() } returns false
		assertFalse(GrandExchangeService.close())
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `close exception path`()
	{
		coEvery { fakeBackend.close() } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { GrandExchangeService.close() } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `close restricted path`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.GRAND_EXCHANGE_DISABLED))
		assertThrows(RestrictionViolation::class.java) { runBlocking { GrandExchangeService.close() } }
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
		coVerify(exactly = 0) { fakeBackend.close() }
	}

	// ── submitBuy ─────────────────────────────────────────────────────────────

	@Test
	fun `submitBuy success path`() = runTest {
		coEvery { fakeBackend.submitBuy(4151, 1, 2_000_000) } returns true
		assertTrue(GrandExchangeService.submitBuy(4151, 1, 2_000_000))
		coVerify(exactly = 1) { fakeBackend.submitBuy(4151, 1, 2_000_000) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `submitBuy failure path`() = runTest {
		coEvery { fakeBackend.submitBuy(4151, 1, 2_000_000) } returns false
		assertFalse(GrandExchangeService.submitBuy(4151, 1, 2_000_000))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `submitBuy exception path`()
	{
		coEvery { fakeBackend.submitBuy(4151, 1, 2_000_000) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { GrandExchangeService.submitBuy(4151, 1, 2_000_000) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `submitBuy restricted path`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.GRAND_EXCHANGE_DISABLED))
		assertThrows(RestrictionViolation::class.java) { runBlocking { GrandExchangeService.submitBuy(4151, 1, 2_000_000) } }
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
		coVerify(exactly = 0) { fakeBackend.submitBuy(any(), any(), any()) }
	}

	// ── submitSell ────────────────────────────────────────────────────────────

	@Test
	fun `submitSell success path`() = runTest {
		coEvery { fakeBackend.submitSell(0, 1_900_000) } returns true
		assertTrue(GrandExchangeService.submitSell(0, 1_900_000))
		coVerify(exactly = 1) { fakeBackend.submitSell(0, 1_900_000) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `submitSell failure path`() = runTest {
		coEvery { fakeBackend.submitSell(0, 1_900_000) } returns false
		assertFalse(GrandExchangeService.submitSell(0, 1_900_000))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `submitSell exception path`()
	{
		coEvery { fakeBackend.submitSell(0, 1_900_000) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { GrandExchangeService.submitSell(0, 1_900_000) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `submitSell restricted path`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.GRAND_EXCHANGE_DISABLED))
		assertThrows(RestrictionViolation::class.java) { runBlocking { GrandExchangeService.submitSell(0, 1_900_000) } }
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
		coVerify(exactly = 0) { fakeBackend.submitSell(any(), any()) }
	}

	// ── collectAll ────────────────────────────────────────────────────────────

	@Test
	fun `collectAll success path`() = runTest {
		coEvery { fakeBackend.collectAll() } returns true
		assertTrue(GrandExchangeService.collectAll())
		coVerify(exactly = 1) { fakeBackend.collectAll() }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `collectAll failure path`() = runTest {
		coEvery { fakeBackend.collectAll() } returns false
		assertFalse(GrandExchangeService.collectAll())
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `collectAll exception path`()
	{
		coEvery { fakeBackend.collectAll() } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { GrandExchangeService.collectAll() } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `collectAll restricted path`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.GRAND_EXCHANGE_DISABLED))
		assertThrows(RestrictionViolation::class.java) { runBlocking { GrandExchangeService.collectAll() } }
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
		coVerify(exactly = 0) { fakeBackend.collectAll() }
	}
}
