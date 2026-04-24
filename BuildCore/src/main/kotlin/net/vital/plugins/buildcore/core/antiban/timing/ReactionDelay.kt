package net.vital.plugins.buildcore.core.antiban.timing

import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.task.GraduatedThrottle

/**
 * Samples a pre-action reaction delay in milliseconds as:
 *
 *   effectiveDelay = personality.nextLogNormal(reactionLogMean, reactionLogStddev)
 *                  × fatigue.reactionMultiplier()
 *                  × throttle.reactionMultiplier()
 *
 * Clamped to [1, 5000]ms.
 *
 * Spec §9.2.
 */
object ReactionDelay {

	fun sample(
		personality: PersonalityVector,
		fatigue: FatigueCurve,
		throttle: GraduatedThrottle?,
		rng: SeededRng,
		mode: InputMode
	): Long {
		require(mode == InputMode.NORMAL) {
			"Plan 4a supports only NORMAL; PRECISION/SURVIVAL land in Plan 4b"
		}
		val baseMs = rng.nextLogNormal(personality.reactionLogMean, personality.reactionLogStddev)
		val fatigueMult = fatigue.reactionMultiplier()
		val throttleMult = throttle?.reactionMultiplier() ?: 1.0
		val raw = baseMs * fatigueMult * throttleMult
		return raw.coerceIn(1.0, 5000.0).toLong()
	}
}
