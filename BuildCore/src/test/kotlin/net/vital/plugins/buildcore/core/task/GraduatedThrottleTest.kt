package net.vital.plugins.buildcore.core.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class GraduatedThrottleTest {

	@Test
	fun `throttle active when account is fresh by XP`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 50_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertTrue(t.active)
	}

	@Test
	fun `throttle active when account created within 24h`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 500_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofHours(12)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertTrue(t.active)
	}

	@Test
	fun `throttle active when explicit fresh flag is set`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 10_000_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(365)),
			explicitFreshFlag = true,
			now = Instant.now()
		)
		assertTrue(t.active)
	}

	@Test
	fun `throttle inactive when account is established`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 500_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertFalse(t.active)
	}

	@Test
	fun `active throttle has reaction multiplier 1_5`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 50_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertEquals(1.5, t.reactionMultiplier, 0.0001)
	}

	@Test
	fun `inactive throttle has multiplier 1_0`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 500_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertEquals(1.0, t.reactionMultiplier, 0.0001)
	}

	@Test
	fun `active throttle reduces XP-per-hour cap to 60 percent`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 50_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertEquals(0.6, t.xpCapMultiplier, 0.0001)
	}

	@Test
	fun `active throttle reduces task-switch cap to 60 percent`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 50_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertEquals(0.6, t.taskSwitchRateMultiplier, 0.0001)
	}
}
