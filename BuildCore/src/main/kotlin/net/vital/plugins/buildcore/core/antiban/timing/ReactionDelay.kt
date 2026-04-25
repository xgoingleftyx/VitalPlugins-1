package net.vital.plugins.buildcore.core.antiban.timing

import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.task.GraduatedThrottle

/**
 * Samples a pre-action reaction delay in milliseconds.
 *
 * NORMAL:
 *   effectiveDelay = personality.nextLogNormal(reactionLogMean, reactionLogStddev)
 *                  × fatigue.reactionMultiplier()
 *                  × throttle.reactionMultiplier()
 *   Clamped to [1, 5000]ms.
 *
 * PRECISION / SURVIVAL:
 *   Uniform sample within [PRECISION_FLOOR_MIN_MS, PRECISION_FLOOR_MAX_MS].
 *   No fatigue, no throttle, no log-normal variance.
 *
 * Spec §9.2. Plan 4b lifts the NORMAL-only invariant.
 */
object ReactionDelay
{
	private const val PRECISION_FLOOR_MIN_MS = 60L
	private const val PRECISION_FLOOR_MAX_MS = 160L

	fun sample(
		personality: PersonalityVector,
		fatigue: FatigueCurve,
		throttle: GraduatedThrottle?,
		rng: SeededRng,
		mode: InputMode
	): Long
	{
		return when (mode)
		{
			InputMode.NORMAL ->
			{
				val baseMs = rng.nextLogNormal(personality.reactionLogMean, personality.reactionLogStddev)
				val fatigueMult = fatigue.reactionMultiplier()
				val throttleMult = throttle?.reactionMultiplier() ?: 1.0
				val raw = baseMs * fatigueMult * throttleMult
				raw.coerceIn(1.0, 5000.0).toLong()
			}
			InputMode.PRECISION, InputMode.SURVIVAL ->
			{
				// Tight floor: uniform within [PRECISION_FLOOR_MIN_MS, PRECISION_FLOOR_MAX_MS).
				// No fatigue, no throttle, no log-normal variance.
				val span = (PRECISION_FLOOR_MAX_MS - PRECISION_FLOOR_MIN_MS).toInt()
				PRECISION_FLOOR_MIN_MS + rng.nextIntInRange(0, span).toLong()
			}
		}
	}
}
