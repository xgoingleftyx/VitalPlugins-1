package net.vital.plugins.buildcore.core.confidence

/**
 * Confidence threshold required for a service action to proceed.
 * Plan 6a spec §4.3.
 */
enum class ActionStakes(val threshold: Double)
{
	READ_ONLY(0.0),    // pass-through; ConfidenceGate not consulted
	LOW(0.4),
	MEDIUM(0.6),
	HIGH(0.8),
	CRITICAL(0.9)
}
