package net.vital.plugins.buildcore.core.confidence

/**
 * Confidence gate consulted by [withServiceCall]. Throws [ConfidenceTooLow]
 * when current score < required threshold. Plan 6a spec §5.2.
 */
object ConfidenceGate
{
	fun check(stakes: ActionStakes)
	{
		if (stakes.threshold <= 0.0) return
		val c = ConfidenceTracker.current()
		if (c.score < stakes.threshold)
		{
			val worst = c.perSignal.minByOrNull { it.value }?.key ?: "unknown"
			throw ConfidenceTooLow(stakes.threshold, c.score, worst)
		}
	}
}
