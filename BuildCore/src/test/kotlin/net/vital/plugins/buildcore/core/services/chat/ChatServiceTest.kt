package net.vital.plugins.buildcore.core.services.chat

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

class ChatServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<ChatBackend>()

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		ChatService.backend = fakeBackend
		ChatService.bus = bus
		ChatService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { ChatService.resetForTests() }

	// ── send ──────────────────────────────────────────────────────────────────

	@Test
	fun `send success path`() = runTest {
		coEvery { fakeBackend.send("hello") } returns true
		assertTrue(ChatService.send("hello"))
		coVerify(exactly = 1) { fakeBackend.send("hello") }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `send failure path`() = runTest {
		coEvery { fakeBackend.send("hello") } returns false
		assertFalse(ChatService.send("hello"))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `send exception path`()
	{
		coEvery { fakeBackend.send("hello") } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { ChatService.send("hello") } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	// ── sendChannel ───────────────────────────────────────────────────────────

	@Test
	fun `sendChannel success path`() = runTest {
		coEvery { fakeBackend.sendChannel(ChatChannel.CLAN, "test") } returns true
		assertTrue(ChatService.sendChannel(ChatChannel.CLAN, "test"))
		coVerify(exactly = 1) { fakeBackend.sendChannel(ChatChannel.CLAN, "test") }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `sendChannel failure path`() = runTest {
		coEvery { fakeBackend.sendChannel(ChatChannel.CLAN, "test") } returns false
		assertFalse(ChatService.sendChannel(ChatChannel.CLAN, "test"))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `sendChannel exception path`()
	{
		coEvery { fakeBackend.sendChannel(ChatChannel.CLAN, "test") } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { ChatService.sendChannel(ChatChannel.CLAN, "test") } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}
}
