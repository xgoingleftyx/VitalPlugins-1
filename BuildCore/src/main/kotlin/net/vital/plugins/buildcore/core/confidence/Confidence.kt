package net.vital.plugins.buildcore.core.confidence

/**
 * Snapshot of confidence at a point in time. Plan 6a spec §4.2.
 */
data class Confidence(
	val score: Double,
	val perSignal: Map<String, Double>,
	val computedAtMs: Long
)
