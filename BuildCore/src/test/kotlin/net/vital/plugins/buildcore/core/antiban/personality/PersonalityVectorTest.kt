package net.vital.plugins.buildcore.core.antiban.personality

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PersonalityVectorTest {

	private fun valid() = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 10.0, mouseCurveWind = 5.0,
		overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.7, foodEatDelayCenterMs = 600, cameraFidgetRatePerMin = 2.0,
		bankWithdrawalPrecision = 0.95, breakBias = BreakBias.DAY_REGULAR,
		misclickRate = 0.01, menuTopSelectionRate = 0.97, idleExamineRatePerMin = 1.5,
		tabSwapRatePerMin = 1.0
	)

	@Test
	fun `constructor with all valid fields succeeds`() {
		valid()   // no throw
	}

	@Test
	fun `out of range mouseCurveGravity is rejected`() {
		assertThrows(IllegalArgumentException::class.java) {
			valid().copy(mouseCurveGravity = 7.5)   // below 8.0
		}
	}
}
