package net.vital.plugins.buildcore.core.confidence

/**
 * Thrown by [ConfidenceGate.check] when current confidence < required threshold.
 * Caught by `withServiceCall` and classified as [ServiceOutcome.UNCONFIDENT].
 * Plan 6a spec §5.1.
 */
class ConfidenceTooLow(
	val required: Double,
	val current: Double,
	val worstSignal: String
) : RuntimeException("confidence $current < required $required (worst signal: $worstSignal)")
