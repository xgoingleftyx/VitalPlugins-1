package net.vital.plugins.buildcore.core.antiban.timing

import net.vital.plugins.buildcore.core.events.EventBus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FatigueCurveTest {

	private class MutableClock(var now: Instant) : Clock() {
		override fun instant(): Instant = now
		override fun getZone() = ZoneOffset.UTC
		override fun withZone(zone: java.time.ZoneId?) = this
	}

	@Test
	fun `reaction multiplier is 1_0 at session start`() {
		val start = Instant.ofEpochSecond(1_000_000)
		val clock = MutableClock(start)
		val fatigue = FatigueCurve(start, clock)
		assertEquals(1.0, fatigue.reactionMultiplier(), 0.001)
	}

	@Test
	fun `reaction multiplier is 1_10 at 4 hours`() {
		val start = Instant.ofEpochSecond(1_000_000)
		val clock = MutableClock(start)
		val fatigue = FatigueCurve(start, clock)
		clock.now = start.plusSeconds(4L * 3600L)
		assertEquals(1.10, fatigue.reactionMultiplier(), 0.001)
	}

	@Test
	fun `reaction multiplier plateaus past 4 hours`() {
		val start = Instant.ofEpochSecond(1_000_000)
		val clock = MutableClock(start)
		val fatigue = FatigueCurve(start, clock)
		clock.now = start.plusSeconds(8L * 3600L)
		assertEquals(1.10, fatigue.reactionMultiplier(), 0.001)
	}
}
