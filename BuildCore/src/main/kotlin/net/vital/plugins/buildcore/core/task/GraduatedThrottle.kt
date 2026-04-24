package net.vital.plugins.buildcore.core.task

import java.time.Duration
import java.time.Instant

/**
 * Fresh-account throttle (spec §6).
 *
 * Can be used in two ways:
 *
 * 1. **Object-style** (Plan 2 API, backward-compatible):
 *    `GraduatedThrottle.evaluate(accountTotalXp, accountCreatedAt, explicitFreshFlag, now)`
 *    returns a [State] with all multipliers.
 *
 * 2. **Instance-style** (Plan 4a API): construct with `(accountAgeDays, totalXp)` and call
 *    `reactionMultiplier()` directly. This is the interface expected by [ReactionDelay].
 *
 * Triggers (ANY → active):
 *   - totalXp < 100_000
 *   - accountCreatedAt within last 24 hours
 *   - explicitFreshFlag = true
 *
 * While active:
 *   - reactionMultiplier = 1.5 (slower reactions)
 *   - xpCapMultiplier = 0.6 (max 60% of declared rate)
 *   - taskSwitchRateMultiplier = 0.6 (40% fewer task switches/hr)
 *
 * Plan 4 multiplies these into the antiban sampling distributions.
 * Plan 2 just produces the values.
 */
class GraduatedThrottle(
	private val accountAgeDays: Int,
	private val totalXp: Long
) {

	private val state: State = evaluate(
		accountTotalXp = totalXp,
		accountCreatedAt = Instant.now().minus(Duration.ofDays(accountAgeDays.toLong())),
		explicitFreshFlag = false,
		now = Instant.now()
	)

	/** Returns the reaction-time multiplier for this account's throttle state. */
	fun reactionMultiplier(): Double = state.reactionMultiplier

	data class State(
		val active: Boolean,
		val reactionMultiplier: Double,
		val xpCapMultiplier: Double,
		val taskSwitchRateMultiplier: Double,
		val reason: String
	)

	companion object {

		private val FRESH_AGE_THRESHOLD: Duration = Duration.ofHours(24)
		private const val FRESH_XP_THRESHOLD: Long = 100_000L

		fun evaluate(
			accountTotalXp: Long,
			accountCreatedAt: Instant?,
			explicitFreshFlag: Boolean,
			now: Instant
		): State {
			val lowXp = accountTotalXp < FRESH_XP_THRESHOLD
			val fresh = accountCreatedAt != null
				&& Duration.between(accountCreatedAt, now).abs() < FRESH_AGE_THRESHOLD

			val active = lowXp || fresh || explicitFreshFlag
			val reason = buildString {
				if (lowXp) append("low total XP ($accountTotalXp < $FRESH_XP_THRESHOLD); ")
				if (fresh) append("account age < 24h; ")
				if (explicitFreshFlag) append("explicit fresh flag set; ")
				if (!active) append("established")
			}.trimEnd(';', ' ')

			return if (active) {
				State(true, 1.5, 0.6, 0.6, reason)
			} else {
				State(false, 1.0, 1.0, 1.0, reason)
			}
		}
	}
}
