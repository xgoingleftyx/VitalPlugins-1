package net.vital.plugins.buildcore.core.confidence

/**
 * The 8 weighted confidence signals. Weights sum to 1.00.
 * Plan 6a spec §4.1.
 */
enum class ConfidenceSignal(val weight: Double, val signalName: String)
{
	INTERFACE_KNOWN         (0.10, "INTERFACE_KNOWN"),
	LAST_ACTION_RESULTED    (0.20, "LAST_ACTION_RESULTED"),
	EXPECTED_ENTITIES_VISIBLE(0.10, "EXPECTED_ENTITIES_VISIBLE"),
	POSITION_REASONABLE     (0.10, "POSITION_REASONABLE"),
	HP_NORMAL               (0.15, "HP_NORMAL"),
	INVENTORY_DELTA_EXPECTED(0.10, "INVENTORY_DELTA_EXPECTED"),
	NO_UNEXPECTED_DIALOG    (0.15, "NO_UNEXPECTED_DIALOG"),
	RECENT_CHAT_NORMAL      (0.10, "RECENT_CHAT_NORMAL");

	companion object
	{
		val ALL: List<ConfidenceSignal> = values().toList()
	}
}
