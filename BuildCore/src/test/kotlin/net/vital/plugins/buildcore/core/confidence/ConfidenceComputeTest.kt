package net.vital.plugins.buildcore.core.confidence

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ConfidenceComputeTest
{
	private val sid = UUID.randomUUID()
	private val gsp = FakeGameStateProvider()
	private var nowMs = 1_000_000L
	private val clock = object : Clock()
	{
		override fun getZone() = ZoneOffset.UTC
		override fun withZone(z: java.time.ZoneId) = this
		override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
		override fun millis(): Long = nowMs
	}

	@BeforeEach
	fun reset()
	{
		ConfidenceTracker.resetForTests()
		ConfidenceTracker.bus = mockk<EventBus>().also { every { it.tryEmit(any()) } returns true }
		ConfidenceTracker.sessionIdProvider = { sid }
		ConfidenceTracker.clock = clock
		ConfidenceTracker.gameStateProvider = gsp
	}

	@Test
	fun `default state yields high confidence`()
	{
		val c = ConfidenceTracker.current()
		assertTrue(c.score > 0.85) { "expected > 0.85 with all-good state, got ${c.score}" }
	}

	@Test
	fun `low HP reduces score`()
	{
		gsp.hp = 0.2
		val c = ConfidenceTracker.current()
		assertEquals(0.0, c.perSignal["HP_NORMAL"])
	}

	@Test
	fun `mid HP yields 0_5`()
	{
		gsp.hp = 0.5
		val c = ConfidenceTracker.current()
		assertEquals(0.5, c.perSignal["HP_NORMAL"])
	}

	@Test
	fun `dialog visible reduces NoUnexpectedDialog signal`()
	{
		gsp.dialogVisible = true
		val c = ConfidenceTracker.current()
		assertEquals(0.5, c.perSignal["NO_UNEXPECTED_DIALOG"])
	}

	@Test
	fun `unknown widget yields 0_5 InterfaceKnown`()
	{
		gsp.widgetId = 999
		val c = ConfidenceTracker.current()
		assertEquals(0.5, c.perSignal["INTERFACE_KNOWN"])
	}

	@Test
	fun `null widget yields 1_0 InterfaceKnown`()
	{
		gsp.widgetId = null
		val c = ConfidenceTracker.current()
		assertEquals(1.0, c.perSignal["INTERFACE_KNOWN"])
	}

	@Test
	fun `LastActionResulted reflects last service outcome SUCCESS`()
	{
		ConfidenceTracker.onServiceCallEnd(ServiceCallEnd(
			sessionId = sid, serviceName = "X", methodName = "m",
			callId = 1L, durationMillis = 5L, outcome = ServiceOutcome.SUCCESS
		))
		nowMs += 700L
		val c = ConfidenceTracker.current()
		assertEquals(1.0, c.perSignal["LAST_ACTION_RESULTED"])
	}

	@Test
	fun `LastActionResulted reflects EXCEPTION as 0_2`()
	{
		ConfidenceTracker.onServiceCallEnd(ServiceCallEnd(
			sessionId = sid, serviceName = "X", methodName = "m",
			callId = 1L, durationMillis = 5L, outcome = ServiceOutcome.EXCEPTION
		))
		nowMs += 700L
		val c = ConfidenceTracker.current()
		assertEquals(0.2, c.perSignal["LAST_ACTION_RESULTED"])
	}

	@Test
	fun `weighted sum equals expected`()
	{
		// All signals at 1.0 (default fake state) → score = sum of weights = 1.0
		val c = ConfidenceTracker.current()
		// HP=1.0, dialog=false→1.0, widget=null→1.0, no service call yet→1.0,
		// 4 stub signals with null hooks → 1.0. Sum should be 1.0.
		assertEquals(1.0, c.score, 0.001)
	}
}
