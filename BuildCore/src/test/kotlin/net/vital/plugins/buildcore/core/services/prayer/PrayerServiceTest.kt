package net.vital.plugins.buildcore.core.services.prayer

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
import vital.api.ui.Prayer
import java.util.UUID

class PrayerServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<PrayerBackend>()
	private val prayer = Prayer.THICK_SKIN

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		PrayerService.backend = fakeBackend
		PrayerService.bus = bus
		PrayerService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { PrayerService.resetForTests() }

	// ── toggle ─────────────────────────────────────────────────────────────

	@Test
	fun `toggle success path`() = runTest {
		coEvery { fakeBackend.toggle(prayer) } returns true
		assertTrue(PrayerService.toggle(prayer))
		coVerify(exactly = 1) { fakeBackend.toggle(prayer) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `toggle failure path`() = runTest {
		coEvery { fakeBackend.toggle(prayer) } returns false
		assertFalse(PrayerService.toggle(prayer))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `toggle exception path`()
	{
		coEvery { fakeBackend.toggle(prayer) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { PrayerService.toggle(prayer) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	// ── flick ──────────────────────────────────────────────────────────────

	@Test
	fun `flick success path`() = runTest {
		coEvery { fakeBackend.flick(prayer) } returns true
		assertTrue(PrayerService.flick(prayer))
		coVerify(exactly = 1) { fakeBackend.flick(prayer) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `flick failure path`() = runTest {
		coEvery { fakeBackend.flick(prayer) } returns false
		assertFalse(PrayerService.flick(prayer))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `flick exception path`()
	{
		coEvery { fakeBackend.flick(prayer) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { PrayerService.flick(prayer) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}
}
