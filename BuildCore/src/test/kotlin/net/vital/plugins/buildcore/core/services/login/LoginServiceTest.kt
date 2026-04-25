package net.vital.plugins.buildcore.core.services.login

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

class LoginServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<LoginBackend>()

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		LoginService.backend = fakeBackend
		LoginService.bus = bus
		LoginService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { LoginService.resetForTests() }

	// ── login ──────────────────────────────────────────────────────────────

	@Test
	fun `login success path`() = runTest {
		coEvery { fakeBackend.login() } returns true
		assertTrue(LoginService.login())
		coVerify(exactly = 1) { fakeBackend.login() }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `login failure path`() = runTest {
		coEvery { fakeBackend.login() } returns false
		assertFalse(LoginService.login())
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `login exception path`()
	{
		coEvery { fakeBackend.login() } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { LoginService.login() } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `login restricted path`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.LOGIN_DISABLED))
		assertThrows(RestrictionViolation::class.java) { runBlocking { LoginService.login() } }
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
		coVerify(exactly = 0) { fakeBackend.login() }
	}

	// ── logout ─────────────────────────────────────────────────────────────

	@Test
	fun `logout success path`() = runTest {
		coEvery { fakeBackend.logout() } returns true
		assertTrue(LoginService.logout())
		coVerify(exactly = 1) { fakeBackend.logout() }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `logout failure path`() = runTest {
		coEvery { fakeBackend.logout() } returns false
		assertFalse(LoginService.logout())
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `logout exception path`()
	{
		coEvery { fakeBackend.logout() } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runBlocking { LoginService.logout() } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `logout restricted path`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.LOGIN_DISABLED))
		assertThrows(RestrictionViolation::class.java) { runBlocking { LoginService.logout() } }
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
		coVerify(exactly = 0) { fakeBackend.logout() }
	}
}
