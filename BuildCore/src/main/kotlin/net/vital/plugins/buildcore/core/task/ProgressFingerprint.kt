package net.vital.plugins.buildcore.core.task

/**
 * Snapshot of "progress signals" used by the watchdog to detect stalls.
 *
 * Two consecutive fingerprints that are equal means no progress. The
 * watchdog (Plan 6) flags a stall after N minutes of unchanged
 * fingerprints; default composition is provided by the [Task] abstract
 * base class, tasks may override to add task-specific signals.
 *
 * Spec §7, §11.
 */
data class ProgressFingerprint(
	val xpTotals: Map<Skill, Long> = emptyMap(),
	val inventoryHash: Int = 0,
	val playerTileHash: Int = 0,
	val openInterfaceId: Int? = null,
	val custom: Map<String, String> = emptyMap()
) {
	companion object {
		val EMPTY: ProgressFingerprint = ProgressFingerprint()
	}
}
