package net.vital.plugins.buildcore.core.services.interact

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
import vital.api.entities.Npc
import vital.api.entities.TileItem
import vital.api.entities.TileObject

class InteractServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<InteractBackend>()

	private val fakeTileObject = mockk<TileObject>(relaxed = true)
	private val fakeNpc = mockk<Npc>(relaxed = true)
	private val fakeTileItem = mockk<TileItem>(relaxed = true)

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		InteractService.backend = fakeBackend
		InteractService.bus = bus
		InteractService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { InteractService.resetForTests() }

	// ── tileObject ────────────────────────────────────────────────────────────

	@Test
	fun `tileObject success path`() = runTest {
		coEvery { fakeBackend.tileObject(fakeTileObject, "Open") } returns true
		assertTrue(InteractService.tileObject(fakeTileObject, "Open"))
		coVerify(exactly = 1) { fakeBackend.tileObject(fakeTileObject, "Open") }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `tileObject failure path`() = runTest {
		coEvery { fakeBackend.tileObject(fakeTileObject, "Open") } returns false
		assertFalse(InteractService.tileObject(fakeTileObject, "Open"))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `tileObject exception path`()
	{
		coEvery { fakeBackend.tileObject(fakeTileObject, "Open") } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { InteractService.tileObject(fakeTileObject, "Open") } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	// ── npc ───────────────────────────────────────────────────────────────────

	@Test
	fun `npc success path`() = runTest {
		coEvery { fakeBackend.npc(fakeNpc, "Talk-to") } returns true
		assertTrue(InteractService.npc(fakeNpc, "Talk-to"))
		coVerify(exactly = 1) { fakeBackend.npc(fakeNpc, "Talk-to") }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `npc failure path`() = runTest {
		coEvery { fakeBackend.npc(fakeNpc, "Talk-to") } returns false
		assertFalse(InteractService.npc(fakeNpc, "Talk-to"))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `npc exception path`()
	{
		coEvery { fakeBackend.npc(fakeNpc, "Talk-to") } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { InteractService.npc(fakeNpc, "Talk-to") } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	// ── tileItem ──────────────────────────────────────────────────────────────

	@Test
	fun `tileItem success path`() = runTest {
		coEvery { fakeBackend.tileItem(fakeTileItem, "Take") } returns true
		assertTrue(InteractService.tileItem(fakeTileItem, "Take"))
		coVerify(exactly = 1) { fakeBackend.tileItem(fakeTileItem, "Take") }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `tileItem failure path`() = runTest {
		coEvery { fakeBackend.tileItem(fakeTileItem, "Take") } returns false
		assertFalse(InteractService.tileItem(fakeTileItem, "Take"))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `tileItem exception path`()
	{
		coEvery { fakeBackend.tileItem(fakeTileItem, "Take") } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { InteractService.tileItem(fakeTileItem, "Take") } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}
}
