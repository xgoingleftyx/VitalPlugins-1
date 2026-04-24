package net.vital.plugins.buildcore.core.antiban.curve

import net.vital.plugins.buildcore.core.antiban.input.Point
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Iterative mouse-path generator after Benjamin J. Land's WindMouse.
 *
 * Each step updates a `wind` vector (random perturbation damped by √3 / √5)
 * and a velocity accumulator pulled toward the target by `gravity`. Velocity
 * magnitude is clamped to a per-step cap sampled from `speedCenter`.
 *
 * Returns (point, delay-since-previous-ms) pairs. The final element is always
 * the exact target with 0-ms delay.
 *
 * Spec §8.
 */
object WindMouse
{
	private const val ITERATION_CAP = 2000

	fun generatePath(
		from: Point,
		to: Point,
		gravity: Double,
		wind: Double,
		speedCenter: Double,
		rng: SeededRng
	): List<Pair<Point, Int>>
	{
		// Edge case — cursor already at target
		if (from == to) return listOf(to to 0)

		val result = mutableListOf<Pair<Point, Int>>()
		var currentX = from.x.toDouble()
		var currentY = from.y.toDouble()
		var velocityX = 0.0
		var velocityY = 0.0
		var windX = 0.0
		var windY = 0.0
		val sqrt3 = sqrt(3.0)
		val sqrt5 = sqrt(5.0)
		val targetX = to.x.toDouble()
		val targetY = to.y.toDouble()

		var iterations = 0
		while (iterations < ITERATION_CAP)
		{
			iterations++
			val dx = targetX - currentX
			val dy = targetY - currentY
			val distance = hypot(dx, dy)
			if (distance < 1.0) break

			val windFactor = min(wind, distance)
			windX = windX / sqrt3 + (rng.nextDouble() * 2.0 - 1.0) * windFactor / sqrt5
			windY = windY / sqrt3 + (rng.nextDouble() * 2.0 - 1.0) * windFactor / sqrt5

			velocityX += windX + gravity * dx / distance
			velocityY += windY + gravity * dy / distance

			val velocityMag = hypot(velocityX, velocityY)
			val stepCap = speedCenter * (3.0 + rng.nextDouble() * 3.0)
			if (velocityMag > stepCap)
			{
				val randomDamping = stepCap / 2.0 + rng.nextDouble() * stepCap / 2.0
				velocityX = velocityX / velocityMag * randomDamping
				velocityY = velocityY / velocityMag * randomDamping
			}

			currentX += velocityX
			currentY += velocityY
			val delayMs = (rng.nextDouble() * 5.0 + 3.0).toInt()
			result += Point(currentX.toInt(), currentY.toInt()) to delayMs
		}

		// Snap to exact target
		result += to to 0
		return result
	}
}
