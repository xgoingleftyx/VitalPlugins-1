package net.vital.plugins.buildcore.core.services.combat

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
import vital.api.entities.Npc
import vital.api.entities.Player
import java.util.UUID

class CombatServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<CombatBackend>()
	private val npc = mockk<Npc>(relaxed = true)
	private val player = mockk<Player>(relaxed = true)
	private val npcTarget = Targetable.NpcTarget(npc)
	private val playerTarget = Targetable.PlayerTarget(player)

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		CombatService.backend = fakeBackend
		CombatService.bus = bus
		CombatService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { CombatService.resetForTests() }

	// ── attack ─────────────────────────────────────────────────────────────

	@Test
	fun `attack npc success path`() = runTest {
		coEvery { fakeBackend.attack(npcTarget) } returns true
		assertTrue(CombatService.attack(npcTarget))
		coVerify(exactly = 1) { fakeBackend.attack(npcTarget) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `attack failure path`() = runTest {
		coEvery { fakeBackend.attack(npcTarget) } returns false
		assertFalse(CombatService.attack(npcTarget))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `attack exception path`()
	{
		coEvery { fakeBackend.attack(npcTarget) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { CombatService.attack(npcTarget) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `attack player success path`() = runTest {
		coEvery { fakeBackend.attack(playerTarget) } returns true
		assertTrue(CombatService.attack(playerTarget))
		coVerify(exactly = 1) { fakeBackend.attack(playerTarget) }
	}

	// ── setAutoRetaliate ───────────────────────────────────────────────────

	@Test
	fun `setAutoRetaliate success path`() = runTest {
		coEvery { fakeBackend.setAutoRetaliate(true) } returns true
		assertTrue(CombatService.setAutoRetaliate(true))
		coVerify(exactly = 1) { fakeBackend.setAutoRetaliate(true) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `setAutoRetaliate failure path`() = runTest {
		coEvery { fakeBackend.setAutoRetaliate(false) } returns false
		assertFalse(CombatService.setAutoRetaliate(false))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `setAutoRetaliate exception path`()
	{
		coEvery { fakeBackend.setAutoRetaliate(true) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { CombatService.setAutoRetaliate(true) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}
}
