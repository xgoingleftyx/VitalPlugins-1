package net.vital.plugins.buildcore.core.antiban.personality

/**
 * Sixteen-dimensional account-stable personality. Values are drawn once per
 * account (seeded by SHA-256(username)) and persisted so the same account
 * always gets the same personality across BuildCore versions.
 *
 * Field declaration order is SEMANTICALLY SIGNIFICANT — reordering silently
 * changes every existing persisted personality. Architecture Test #6 enforces
 * the order matches [PersonalityGenerator.generate].
 *
 * Ranges match foundation spec §9; [require] guards reject out-of-range values
 * on load (disk-tampering / corrupted-file guard).
 *
 * Spec §6.1.
 */
data class PersonalityVector(
	val schemaVersion: Int = 1,
	val mouseSpeedCenter:        Double,
	val mouseCurveGravity:       Double,
	val mouseCurveWind:          Double,
	val overshootTendency:       Double,
	val reactionLogMean:         Double,
	val reactionLogStddev:       Double,
	val hotkeyPreference:        Double,
	val foodEatDelayCenterMs:    Int,
	val cameraFidgetRatePerMin:  Double,
	val bankWithdrawalPrecision: Double,
	val breakBias:               BreakBias,
	val misclickRate:            Double,
	val menuTopSelectionRate:    Double,
	val idleExamineRatePerMin:   Double,
	val tabSwapRatePerMin:       Double
) {
	init {
		require(schemaVersion == 1) { "unsupported schemaVersion=$schemaVersion" }
		require(mouseSpeedCenter        in 0.6..1.8)    { "mouseSpeedCenter out of range: $mouseSpeedCenter" }
		require(mouseCurveGravity       in 8.0..12.0)   { "mouseCurveGravity out of range: $mouseCurveGravity" }
		require(mouseCurveWind          in 3.0..7.0)    { "mouseCurveWind out of range: $mouseCurveWind" }
		require(overshootTendency       in 0.02..0.12)  { "overshootTendency out of range: $overshootTendency" }
		require(reactionLogMean         in 5.5..6.5)    { "reactionLogMean out of range: $reactionLogMean" }
		require(reactionLogStddev       in 0.3..0.5)    { "reactionLogStddev out of range: $reactionLogStddev" }
		require(hotkeyPreference        in 0.4..0.9)    { "hotkeyPreference out of range: $hotkeyPreference" }
		require(foodEatDelayCenterMs    in 400..900)    { "foodEatDelayCenterMs out of range: $foodEatDelayCenterMs" }
		require(cameraFidgetRatePerMin  in 0.8..3.5)    { "cameraFidgetRatePerMin out of range: $cameraFidgetRatePerMin" }
		require(bankWithdrawalPrecision in 0.85..0.99)  { "bankWithdrawalPrecision out of range: $bankWithdrawalPrecision" }
		require(misclickRate            in 0.003..0.015){ "misclickRate out of range: $misclickRate" }
		require(menuTopSelectionRate    in 0.92..0.995) { "menuTopSelectionRate out of range: $menuTopSelectionRate" }
		require(idleExamineRatePerMin   in 0.5..2.5)    { "idleExamineRatePerMin out of range: $idleExamineRatePerMin" }
		require(tabSwapRatePerMin       in 0.3..1.8)    { "tabSwapRatePerMin out of range: $tabSwapRatePerMin" }
	}
}

enum class BreakBias { NIGHT_OWL, DAY_REGULAR, BURST }
