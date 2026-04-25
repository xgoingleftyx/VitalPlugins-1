package net.vital.plugins.buildcore.core.antiban.timing

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.FatigueUpdated
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * Session-scoped fatigue multipliers. Linear interp from 1.0 at session start
 * to spec-pinned midpoints at 4h, clamped past 4h.
 *
 * Emits a debounced [FatigueUpdated] event: at most once per 60s AND only when
 * the reaction multiplier has drifted ≥0.01 since the last emission.
 *
 * Spec §9.3.
 */
class FatigueCurve(
	private val sessionStart: Instant,
	private val clock: Clock = Clock.systemUTC(),
	private val bus: EventBus? = null,
	private val sessionIdProvider: () -> UUID = { UUID(0, 0) }
) {

	@Volatile private var lastEmitAt: Instant = Instant.EPOCH
	@Volatile private var lastEmitReaction: Double = 1.0

	fun reactionMultiplier(): Double = multiplier(MAX_REACTION_CREEP).also { maybeEmit(it) }
	fun misclickMultiplier(): Double = multiplier(MAX_MISCLICK_CREEP)
	/** Plan 4b service layer consumes this via the [FatigueUpdated] event payload. */
	fun overshootVarianceMultiplier(): Double = multiplier(MAX_OVERSHOOT_VARIANCE_CREEP)
	/** Plan 4b service layer consumes this via the [FatigueUpdated] event payload. */
	fun fidgetRateMultiplier(): Double = multiplier(MAX_FIDGET_CREEP)

	private fun multiplier(maxCreep: Double): Double {
		val ageMs = Duration.between(sessionStart, clock.instant()).toMillis().coerceAtLeast(0)
		val t = (ageMs.toDouble() / FOUR_HOURS_MS).coerceIn(0.0, 1.0)
		return 1.0 + t * maxCreep
	}

	private fun maybeEmit(reactionMult: Double) {
		val bus = bus ?: return
		val now = clock.instant()
		val sinceLast = Duration.between(lastEmitAt, now)
		val drift = abs(reactionMult - lastEmitReaction)
		if (sinceLast.seconds < 60L && drift < 0.01) return
		lastEmitAt = now
		lastEmitReaction = reactionMult
		val ageMs = Duration.between(sessionStart, now).toMillis().coerceAtLeast(0)
		bus.tryEmit(FatigueUpdated(
			sessionId = sessionIdProvider(),
			sessionAgeMillis = ageMs,
			reactionMultiplier = reactionMult,
			misclickMultiplier = misclickMultiplier(),
			overshootVarianceMultiplier = overshootVarianceMultiplier(),
			fidgetRateMultiplier = fidgetRateMultiplier()
		))
	}

	companion object {
		private const val FOUR_HOURS_MS: Long = 4L * 60L * 60L * 1000L
		private const val MAX_REACTION_CREEP = 0.10
		private const val MAX_MISCLICK_CREEP = 0.35
		private const val MAX_OVERSHOOT_VARIANCE_CREEP = 0.25
		private const val MAX_FIDGET_CREEP = 0.40
	}
}
