package net.vital.plugins.buildcore.core.antiban.rng

import java.security.SecureRandom

/**
 * Per-session RNG. Seed is drawn once from [SecureRandom] (unpredictable) and
 * exposed as a public `val` so Plan 4c's replay recorder can log it at session
 * start. All subsequent draws go through [JavaUtilRng] (deterministic from seed).
 *
 * The class-delegation pattern (`SeededRng by delegate`) inherits every
 * [SeededRng] method without boilerplate.
 *
 * Spec §5.4.
 */
class SessionRng private constructor(
	val seed: Long,
	private val delegate: SeededRng
) : SeededRng by delegate {

	companion object {
		fun fresh(): SessionRng {
			val seed = SecureRandom().nextLong()
			return SessionRng(seed, JavaUtilRng(seed))
		}

		/** Deterministic constructor for tests and (future) replay. */
		fun fromSeed(seed: Long): SessionRng = SessionRng(seed, JavaUtilRng(seed))
	}
}
