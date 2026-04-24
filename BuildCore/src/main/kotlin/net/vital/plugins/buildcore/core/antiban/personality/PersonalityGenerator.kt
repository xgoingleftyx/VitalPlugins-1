package net.vital.plugins.buildcore.core.antiban.personality

import net.vital.plugins.buildcore.core.antiban.rng.SeededRng

/**
 * Pure function from [SeededRng] to [PersonalityVector].
 *
 * Draw order matches [PersonalityVector]'s field declaration order. Architecture
 * Test #6 enforces this — reordering draws would silently change every existing
 * persisted personality. Future plans that add a 16th+ dimension must append at
 * the END and bump [PersonalityVector.schemaVersion].
 *
 * Spec §6.2.
 */
object PersonalityGenerator {

	fun generate(rng: SeededRng): PersonalityVector = PersonalityVector(
		mouseSpeedCenter        = rng.nextDoubleInRange(0.6, 1.8),
		mouseCurveGravity       = rng.nextDoubleInRange(8.0, 12.0),
		mouseCurveWind          = rng.nextDoubleInRange(3.0, 7.0),
		overshootTendency       = rng.nextDoubleInRange(0.02, 0.12),
		reactionLogMean         = rng.nextDoubleInRange(5.5, 6.5),
		reactionLogStddev       = rng.nextDoubleInRange(0.3, 0.5),
		hotkeyPreference        = rng.nextDoubleInRange(0.4, 0.9),
		foodEatDelayCenterMs    = rng.nextIntInRange(400, 901),   // until is exclusive, +1 so 900 is reachable
		cameraFidgetRatePerMin  = rng.nextDoubleInRange(0.8, 3.5),
		bankWithdrawalPrecision = rng.nextDoubleInRange(0.85, 0.99),
		breakBias               = BreakBias.entries[rng.nextIntInRange(0, 3)],
		misclickRate            = rng.nextDoubleInRange(0.003, 0.015),
		menuTopSelectionRate    = rng.nextDoubleInRange(0.92, 0.995),
		idleExamineRatePerMin   = rng.nextDoubleInRange(0.5, 2.5),
		tabSwapRatePerMin       = rng.nextDoubleInRange(0.3, 1.8)
	)
}
