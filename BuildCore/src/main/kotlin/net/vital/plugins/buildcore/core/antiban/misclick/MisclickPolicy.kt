package net.vital.plugins.buildcore.core.antiban.misclick

import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.events.Misclick
import java.util.UUID

/**
 * Primitive-layer misclick policy. [decide] returns true when a click should
 * be jittered; [sampleOffset] picks the (dx, dy). [recordPixelJitter] /
 * [recordNearMiss] emit the matching [Misclick] events (the actual click
 * dispatch happens in `Mouse.click` — Task 17).
 *
 * Plan 4b spec §6.1.
 */
object MisclickPolicy
{
	@Volatile var bus: EventBus? = null
	@Volatile var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	internal const val NEAR_MISS_FRACTION = 0.10
	internal const val BURST_COOLDOWN_MS = 2_000L

	@Volatile private var lastMisclickAtMs: Long = 0L

	fun decide(
		personality: PersonalityVector,
		fatigue: FatigueCurve,
		rng: SeededRng,
		mode: InputMode,
		nowMs: Long = System.currentTimeMillis()
	): Boolean
	{
		if (mode != InputMode.NORMAL) return false
		if (nowMs - lastMisclickAtMs < BURST_COOLDOWN_MS) return false
		val p = personality.misclickRate * fatigue.misclickMultiplier()
		val hit = rng.nextDouble() < p
		if (hit) lastMisclickAtMs = nowMs
		return hit
	}

	fun pickKind(rng: SeededRng): MisclickKind =
		if (rng.nextDouble() < NEAR_MISS_FRACTION) MisclickKind.NEAR_MISS else MisclickKind.PIXEL_JITTER

	fun samplePixelJitterOffset(rng: SeededRng): Pair<Int, Int>
	{
		val dx = (rng.nextGaussian() * 1.5).toInt().coerceIn(-3, 3)
		val dy = (rng.nextGaussian() * 1.5).toInt().coerceIn(-3, 3)
		return dx to dy
	}

	fun sampleNearMissOffset(rng: SeededRng): Pair<Int, Int>
	{
		val dx = rng.nextIntInRange(5, 12) * if (rng.nextBoolean(0.5)) 1 else -1
		val dy = rng.nextIntInRange(5, 12) * if (rng.nextBoolean(0.5)) 1 else -1
		return dx to dy
	}

	fun recordPixelJitter(intendedX: Int, intendedY: Int, dx: Int, dy: Int)
	{
		bus?.tryEmit(Misclick(
			sessionId = sessionIdProvider(),
			kind = MisclickKind.PIXEL_JITTER,
			intendedX = intendedX, intendedY = intendedY,
			actualX = intendedX + dx, actualY = intendedY + dy,
			corrected = false
		))
	}

	fun recordNearMiss(intendedX: Int, intendedY: Int, dx: Int, dy: Int)
	{
		bus?.tryEmit(Misclick(
			sessionId = sessionIdProvider(),
			kind = MisclickKind.NEAR_MISS,
			intendedX = intendedX, intendedY = intendedY,
			actualX = intendedX + dx, actualY = intendedY + dy,
			corrected = true
		))
	}

	internal fun resetForTests()
	{
		lastMisclickAtMs = 0L
		bus = null
	}
}
