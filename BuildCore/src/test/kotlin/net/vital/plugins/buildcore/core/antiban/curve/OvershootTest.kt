package net.vital.plugins.buildcore.core.antiban.curve

import net.vital.plugins.buildcore.core.antiban.input.FakeMouseBackend
import net.vital.plugins.buildcore.core.antiban.input.Point
import net.vital.plugins.buildcore.core.antiban.personality.BreakBias
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OvershootTest
{
	private fun samplePersonality() = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 10.0, mouseCurveWind = 5.0,
		overshootTendency = 0.08, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.7, foodEatDelayCenterMs = 600, cameraFidgetRatePerMin = 2.0,
		bankWithdrawalPrecision = 0.95, breakBias = BreakBias.DAY_REGULAR,
		misclickRate = 0.01, menuTopSelectionRate = 0.97, idleExamineRatePerMin = 1.5,
		tabSwapRatePerMin = 1.0
	)

	@Test
	fun `overshoot pushes the trail past the target and returns`()
	{
		val backend = FakeMouseBackend()
		backend.position = Point(100, 100)
		val target = Point(500, 100)
		Overshoot.apply(backend.currentPosition(), target, backend,
			samplePersonality(), JavaUtilRng(42L))
		// At least one recorded trail point must have x > target.x (i.e. overshot)
		val overshootPointExists = backend.trailPoints.any { it.x > target.x }
		assertTrue(overshootPointExists,
			"expected at least one trail point with x > ${target.x}, got ${backend.trailPoints.takeLast(5)}")
	}
}
