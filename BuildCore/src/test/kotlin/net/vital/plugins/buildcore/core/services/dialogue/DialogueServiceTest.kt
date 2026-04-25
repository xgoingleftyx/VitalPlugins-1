package net.vital.plugins.buildcore.core.services.dialogue

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
import net.vital.plugins.buildcore.core.services.ServiceCallContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class DialogueServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<DialogueBackend>()

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		DialogueService.backend = fakeBackend
		DialogueService.bus = bus
		DialogueService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { DialogueService.resetForTests() }

	// ── continueAll ───────────────────────────────────────────────────────────

	@Test
	fun `continueAll success path`() = runTest {
		coEvery { fakeBackend.continueAll() } returns true
		assertTrue(DialogueService.continueAll())
		coVerify(exactly = 1) { fakeBackend.continueAll() }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `continueAll failure path`() = runTest {
		coEvery { fakeBackend.continueAll() } returns false
		assertFalse(DialogueService.continueAll())
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `continueAll exception path`()
	{
		coEvery { fakeBackend.continueAll() } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { DialogueService.continueAll() } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	// ── chooseOption ──────────────────────────────────────────────────────────

	@Test
	fun `chooseOption success path`() = runTest {
		coEvery { fakeBackend.chooseOption("Yes") } returns true
		assertTrue(DialogueService.chooseOption("Yes"))
		coVerify(exactly = 1) { fakeBackend.chooseOption("Yes") }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `chooseOption failure path`() = runTest {
		coEvery { fakeBackend.chooseOption("Yes") } returns false
		assertFalse(DialogueService.chooseOption("Yes"))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `chooseOption exception path`()
	{
		coEvery { fakeBackend.chooseOption("Yes") } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { DialogueService.chooseOption("Yes") } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	// ── close ─────────────────────────────────────────────────────────────────

	@Test
	fun `close success path`() = runTest {
		coEvery { fakeBackend.close() } returns true
		assertTrue(DialogueService.close())
		coVerify(exactly = 1) { fakeBackend.close() }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `close failure path`() = runTest {
		coEvery { fakeBackend.close() } returns false
		assertFalse(DialogueService.close())
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `close exception path`()
	{
		coEvery { fakeBackend.close() } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { DialogueService.close() } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}
}
