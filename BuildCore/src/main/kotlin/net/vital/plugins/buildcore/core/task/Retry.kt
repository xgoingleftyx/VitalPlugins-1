package net.vital.plugins.buildcore.core.task

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Per-task retry policy.
 *
 * Spec §6. Defaults: 3 attempts, exponential backoff 30s→600s,
 * skip-if-optional-else-safe-stop on exhaustion.
 */
data class RetryPolicy(
	val maxAttempts: Int = 3,
	val backoff: Backoff = Backoff.Exponential(base = 30.seconds, max = 600.seconds),
	val onExhausted: OnExhausted = OnExhausted.SKIP_IF_OPTIONAL_ELSE_SAFE_STOP,
	val resetOnSuccess: Boolean = true
) {
	init {
		require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
	}

	companion object {
		val DEFAULT: RetryPolicy = RetryPolicy()
	}

	/**
	 * Compute the delay before attempt #[attemptNumber] (1-indexed).
	 * Attempt 1 has zero delay; subsequent attempts use [backoff].
	 */
	fun delayBefore(attemptNumber: Int): Duration {
		require(attemptNumber >= 1) { "attemptNumber is 1-indexed" }
		if (attemptNumber == 1) return Duration.ZERO
		return backoff.delayFor(attemptNumber)
	}
}

sealed class Backoff {
	abstract fun delayFor(attemptNumber: Int): Duration

	/** Constant [delay] between attempts. */
	data class Constant(val delay: Duration) : Backoff() {
		override fun delayFor(attemptNumber: Int): Duration = delay
	}

	/** Doubled each retry starting from [base], capped at [max]. */
	data class Exponential(val base: Duration, val max: Duration) : Backoff() {
		init { require(max >= base) { "max ($max) must be >= base ($base)" } }
		override fun delayFor(attemptNumber: Int): Duration {
			val factor = 1 shl (attemptNumber - 2).coerceAtLeast(0)
			val raw = base * factor
			return if (raw > max) max else raw
		}
	}
}

enum class OnExhausted {
	SKIP_IF_OPTIONAL_ELSE_SAFE_STOP,
	SAFE_STOP_ALWAYS,
	SKIP_ALWAYS
}
