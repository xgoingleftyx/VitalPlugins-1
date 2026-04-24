package net.vital.plugins.buildcore.core.antiban.timing

import net.vital.plugins.buildcore.core.antiban.personality.BreakBias
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.task.GraduatedThrottle
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

class GraduatedThrottleIntegrationTest {

	private fun samplePersonality() = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 10.0, mouseCurveWind = 5.0,
		overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.7, foodEatDelayCenterMs = 600, cameraFidgetRatePerMin = 2.0,
		bankWithdrawalPrecision = 0.95, breakBias = BreakBias.DAY_REGULAR,
		misclickRate = 0.01, menuTopSelectionRate = 0.97, idleExamineRatePerMin = 1.5,
		tabSwapRatePerMin = 1.0
	)

	@Test
	fun `fresh throttle produces longer delay than mature throttle on same seed`() {
		val p = samplePersonality()
		val fatigue = FatigueCurve(Instant.ofEpochSecond(0), Clock.fixed(Instant.ofEpochSecond(0), java.time.ZoneOffset.UTC))
		val fresh = GraduatedThrottle(accountAgeDays = 0, totalXp = 0L)
		val mature = GraduatedThrottle(accountAgeDays = 999, totalXp = Long.MAX_VALUE)
		val freshDelay = ReactionDelay.sample(p, fatigue, fresh, JavaUtilRng(42L), InputMode.NORMAL)
		val matureDelay = ReactionDelay.sample(p, fatigue, mature, JavaUtilRng(42L), InputMode.NORMAL)
		assertTrue(freshDelay > matureDelay, "fresh=$freshDelay matureDelay=$matureDelay")
	}
}
