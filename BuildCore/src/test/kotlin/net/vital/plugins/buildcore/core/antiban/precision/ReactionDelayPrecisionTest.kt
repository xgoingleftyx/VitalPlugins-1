package net.vital.plugins.buildcore.core.antiban.precision

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.antiban.timing.ReactionDelay
import net.vital.plugins.buildcore.core.events.InputMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReactionDelayPrecisionTest
{
	private val personality = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 9.0, mouseCurveWind = 5.0,
		overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.6, foodEatDelayCenterMs = 600,
		cameraFidgetRatePerMin = 1.5, bankWithdrawalPrecision = 0.95,
		breakBias = net.vital.plugins.buildcore.core.antiban.personality.BreakBias.DAY_REGULAR,
		misclickRate = 0.005, menuTopSelectionRate = 0.95,
		idleExamineRatePerMin = 1.0, tabSwapRatePerMin = 0.8
	)
	private val fatigue = mockk<FatigueCurve>().also {
		every { it.reactionMultiplier() } returns 1.5
		every { it.misclickMultiplier() } returns 1.0
	}
	private val rng = JavaUtilRng(seed = 42L)

	@Test
	fun `NORMAL applies fatigue multiplier`()
	{
		val n1 = ReactionDelay.sample(personality, fatigue, throttle = null, rng = JavaUtilRng(seed = 42L), mode = InputMode.NORMAL)
		assertTrue(n1 > 100L) // log-normal × 1.5 fatigue, should be substantial
	}

	@Test
	fun `PRECISION uses tight floor and ignores fatigue`()
	{
		val p = ReactionDelay.sample(personality, fatigue, throttle = null, rng = JavaUtilRng(seed = 42L), mode = InputMode.PRECISION)
		assertTrue(p in 60L..160L) { "PRECISION delay $p outside tight floor band 60..160ms" }
	}

	@Test
	fun `SURVIVAL behaves like PRECISION timing-wise`()
	{
		val s = ReactionDelay.sample(personality, fatigue, throttle = null, rng = JavaUtilRng(seed = 42L), mode = InputMode.SURVIVAL)
		assertTrue(s in 60L..160L) { "SURVIVAL delay $s outside tight floor band 60..160ms" }
	}
}
