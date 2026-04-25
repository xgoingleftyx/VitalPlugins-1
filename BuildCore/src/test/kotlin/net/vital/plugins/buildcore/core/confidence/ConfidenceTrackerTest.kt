package net.vital.plugins.buildcore.core.confidence

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.ConfidenceUpdated
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

class ConfidenceTrackerTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also { every { it.tryEmit(any()) } answers { captured.add(firstArg()); true } }
	private val sid = UUID.randomUUID()
	private var nowMs = 0L
	private val clock = object : Clock()
	{
		override fun getZone() = ZoneOffset.UTC
		override fun withZone(z: java.time.ZoneId) = this
		override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
		override fun millis(): Long = nowMs
	}
	private val gsp = FakeGameStateProvider()

	@BeforeEach
	fun reset()
	{
		captured.clear()
		nowMs = 1_000_000L
		ConfidenceTracker.resetForTests()
		ConfidenceTracker.bus = bus
		ConfidenceTracker.sessionIdProvider = { sid }
		ConfidenceTracker.clock = clock
		ConfidenceTracker.gameStateProvider = gsp
	}

	@Test
	fun `current emits ConfidenceUpdated on first compute`()
	{
		ConfidenceTracker.current()
		assertTrue(captured.any { it is ConfidenceUpdated })
	}

	@Test
	fun `current returns cached within 600ms TTL`()
	{
		val c1 = ConfidenceTracker.current()
		nowMs += 500L
		val c2 = ConfidenceTracker.current()
		assertSame(c1, c2)   // cache hit
		assertEquals(1, captured.count { it is ConfidenceUpdated })
	}

	@Test
	fun `current recomputes after 600ms TTL`()
	{
		ConfidenceTracker.current()
		nowMs += 700L
		ConfidenceTracker.current()
		assertEquals(2, captured.count { it is ConfidenceUpdated })
	}

	@Test
	fun `onServiceCallEnd invalidates cache`()
	{
		val c1 = ConfidenceTracker.current()
		ConfidenceTracker.onServiceCallEnd(ServiceCallEnd(
			sessionId = sid, serviceName = "X", methodName = "m",
			callId = 1L, durationMillis = 5L, outcome = ServiceOutcome.SUCCESS
		))
		val c2 = ConfidenceTracker.current()
		assertNotSame(c1, c2)
	}

	@Test
	fun `UNCONFIDENT outcome does not overwrite lastActionOutcome`()
	{
		ConfidenceTracker.onServiceCallEnd(ServiceCallEnd(
			sessionId = sid, serviceName = "X", methodName = "m",
			callId = 1L, durationMillis = 5L, outcome = ServiceOutcome.SUCCESS
		))
		val before = ConfidenceTracker.current()
		ConfidenceTracker.onServiceCallEnd(ServiceCallEnd(
			sessionId = sid, serviceName = "X", methodName = "m",
			callId = 2L, durationMillis = 5L, outcome = ServiceOutcome.UNCONFIDENT
		))
		nowMs += 700L
		val after = ConfidenceTracker.current()
		// LastActionResulted should still reflect SUCCESS=1.0
		assertEquals(1.0, after.perSignal["LAST_ACTION_RESULTED"])
	}
}

/** Test fake — returns "all good" by default; tests can override fields. */
internal class FakeGameStateProvider : GameStateProvider
{
	var widgetId: Int? = null
	var dialogVisible: Boolean = false
	var hp: Double? = 1.0
	var tileX: Int? = 3200
	var tileY: Int? = 3200
	var npcCounts: Map<String, Int> = emptyMap()
	var objectCounts: Map<String, Int> = emptyMap()
	var inventoryCounts: Map<Int, Int> = emptyMap()

	override fun openWidgetId(): Int? = widgetId
	override fun isDialogVisible(): Boolean = dialogVisible
	override fun hpRatio(): Double? = hp
	override fun playerTileX(): Int? = tileX
	override fun playerTileY(): Int? = tileY
	override fun npcCountByName(name: String): Int = npcCounts[name] ?: 0
	override fun objectCountByName(name: String): Int = objectCounts[name] ?: 0
	override fun inventoryCountById(itemId: Int): Int = inventoryCounts[itemId] ?: 0
}
