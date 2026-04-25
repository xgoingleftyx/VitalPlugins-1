package net.vital.plugins.buildcore.core.services.magic

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
import net.vital.plugins.buildcore.core.services.combat.Targetable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import vital.api.entities.Npc
import vital.api.ui.Spell
import java.util.UUID

class MagicServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<MagicBackend>()
	private val spell = Spell.WIND_STRIKE
	private val npc = mockk<Npc>(relaxed = true)
	private val target = Targetable.NpcTarget(npc)

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		MagicService.backend = fakeBackend
		MagicService.bus = bus
		MagicService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { MagicService.resetForTests() }

	// ── cast ───────────────────────────────────────────────────────────────

	@Test
	fun `cast success path`() = runTest {
		coEvery { fakeBackend.cast(spell) } returns true
		assertTrue(MagicService.cast(spell))
		coVerify(exactly = 1) { fakeBackend.cast(spell) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `cast failure path`() = runTest {
		coEvery { fakeBackend.cast(spell) } returns false
		assertFalse(MagicService.cast(spell))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `cast exception path`()
	{
		coEvery { fakeBackend.cast(spell) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { MagicService.cast(spell) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	// ── castOn ─────────────────────────────────────────────────────────────

	@Test
	fun `castOn success path`() = runTest {
		coEvery { fakeBackend.castOn(spell, target) } returns true
		assertTrue(MagicService.castOn(spell, target))
		coVerify(exactly = 1) { fakeBackend.castOn(spell, target) }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `castOn failure path`() = runTest {
		coEvery { fakeBackend.castOn(spell, target) } returns false
		assertFalse(MagicService.castOn(spell, target))
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `castOn exception path`()
	{
		coEvery { fakeBackend.castOn(spell, target) } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { MagicService.castOn(spell, target) } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}
}
