package net.vital.plugins.buildcore.core.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {

	@Test
	fun `attempt 1 has zero delay`() {
		val policy = RetryPolicy()
		assertEquals(Duration.ZERO, policy.delayBefore(1))
	}

	@Test
	fun `exponential backoff doubles each attempt capped at max`() {
		val policy = RetryPolicy(
			backoff = Backoff.Exponential(base = 10.seconds, max = 200.seconds)
		)
		assertEquals(10.seconds, policy.delayBefore(2))
		assertEquals(20.seconds, policy.delayBefore(3))
		assertEquals(40.seconds, policy.delayBefore(4))
		assertEquals(80.seconds, policy.delayBefore(5))
		assertEquals(160.seconds, policy.delayBefore(6))
		assertEquals(200.seconds, policy.delayBefore(7)) // capped
		assertEquals(200.seconds, policy.delayBefore(10)) // still capped
	}

	@Test
	fun `constant backoff returns same delay each attempt`() {
		val policy = RetryPolicy(backoff = Backoff.Constant(45.seconds))
		assertEquals(Duration.ZERO, policy.delayBefore(1))
		assertEquals(45.seconds, policy.delayBefore(2))
		assertEquals(45.seconds, policy.delayBefore(3))
	}

	@Test
	fun `delayBefore rejects zero or negative attempt numbers`() {
		val policy = RetryPolicy()
		assertThrows(IllegalArgumentException::class.java) { policy.delayBefore(0) }
		assertThrows(IllegalArgumentException::class.java) { policy.delayBefore(-1) }
	}

	@Test
	fun `exponential backoff rejects max less than base`() {
		assertThrows(IllegalArgumentException::class.java) {
			Backoff.Exponential(base = 100.seconds, max = 10.seconds)
		}
	}

	@Test
	fun `maxAttempts must be at least 1`() {
		assertThrows(IllegalArgumentException::class.java) {
			RetryPolicy(maxAttempts = 0)
		}
	}
}
