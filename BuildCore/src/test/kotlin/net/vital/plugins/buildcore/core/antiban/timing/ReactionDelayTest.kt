package net.vital.plugins.buildcore.core.antiban.timing

import net.vital.plugins.buildcore.core.antiban.personality.BreakBias
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.events.InputMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

class ReactionDelayTest {

	private fun samplePersonality() = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 10.0, mouseCurveWind = 5.0,
		overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.7, foodEatDelayCenterMs = 600, cameraFidgetRatePerMin = 2.0,
		bankWithdrawalPrecision = 0.95, breakBias = BreakBias.DAY_REGULAR,
		misclickRate = 0.01, menuTopSelectionRate = 0.97, idleExamineRatePerMin = 1.5,
		tabSwapRatePerMin = 1.0
	)

	@Test
	fun `same seed and state produce identical delay`() {
		val personality = samplePersonality()
		val fatigue = FatigueCurve(Instant.ofEpochSecond(0), Clock.fixed(Instant.ofEpochSecond(0), java.time.ZoneOffset.UTC))
		val a = ReactionDelay.sample(personality, fatigue, null, JavaUtilRng(42L), InputMode.NORMAL)
		val b = ReactionDelay.sample(personality, fatigue, null, JavaUtilRng(42L), InputMode.NORMAL)
		assertEquals(a, b)
	}

	@Test
	fun `delay is within bounded range`() {
		val personality = samplePersonality()
		val fatigue = FatigueCurve(Instant.ofEpochSecond(0), Clock.fixed(Instant.ofEpochSecond(0), java.time.ZoneOffset.UTC))
		repeat(100) { seed ->
			val delay = ReactionDelay.sample(personality, fatigue, null, JavaUtilRng(seed.toLong()), InputMode.NORMAL)
			assertTrue(delay in 1L..5000L, "delay=$delay out of bounds on seed=$seed")
		}
	}

}
