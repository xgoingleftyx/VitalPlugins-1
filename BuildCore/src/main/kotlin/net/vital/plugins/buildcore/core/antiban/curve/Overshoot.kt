package net.vital.plugins.buildcore.core.antiban.curve

import net.vital.plugins.buildcore.core.antiban.input.MouseBackend
import net.vital.plugins.buildcore.core.antiban.input.Point
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import kotlin.math.hypot

/**
 * Two-phase overshoot: fly past the target by 3-12 pixels along the approach
 * vector, then return via a second WindMouse pass.
 *
 * Spec §8.3.
 */
object Overshoot
{
	fun apply(
		current: Point,
		target: Point,
		backend: MouseBackend,
		personality: PersonalityVector,
		rng: SeededRng
	)
	{
		val dx = (target.x - current.x).toDouble()
		val dy = (target.y - current.y).toDouble()
		val mag = hypot(dx, dy).coerceAtLeast(1.0)
		val overshootDistance = 3.0 + rng.nextDoubleInRange(0.0, 9.0)
		val overshootX = target.x + (dx / mag * overshootDistance).toInt()
		val overshootY = target.y + (dy / mag * overshootDistance).toInt()

		val pathOut = WindMouse.generatePath(
			current, Point(overshootX, overshootY),
			personality.mouseCurveGravity, personality.mouseCurveWind,
			personality.mouseSpeedCenter, rng
		)
		for ((p, _) in pathOut) backend.appendTrailPoint(p.x, p.y)

		val pathBack = WindMouse.generatePath(
			Point(overshootX, overshootY), target,
			personality.mouseCurveGravity, personality.mouseCurveWind,
			personality.mouseSpeedCenter, rng
		)
		for ((p, _) in pathBack) backend.appendTrailPoint(p.x, p.y)
	}
}
