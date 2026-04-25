package net.vital.plugins.buildcore.core.confidence

/**
 * The 8 weighted confidence signals. Weights sum to 1.00.
 * Plan 6a spec §4.1.
 */
sealed class ConfidenceSignal(val weight: Double, val name: String)
{
	object InterfaceKnown          : ConfidenceSignal(0.10, "INTERFACE_KNOWN")
	object LastActionResulted      : ConfidenceSignal(0.20, "LAST_ACTION_RESULTED")
	object ExpectedEntitiesVisible : ConfidenceSignal(0.10, "EXPECTED_ENTITIES_VISIBLE")
	object PositionReasonable      : ConfidenceSignal(0.10, "POSITION_REASONABLE")
	object HpNormal                : ConfidenceSignal(0.15, "HP_NORMAL")
	object InventoryDeltaExpected  : ConfidenceSignal(0.10, "INVENTORY_DELTA_EXPECTED")
	object NoUnexpectedDialog      : ConfidenceSignal(0.15, "NO_UNEXPECTED_DIALOG")
	object RecentChatNormal        : ConfidenceSignal(0.10, "RECENT_CHAT_NORMAL")

	companion object
	{
		val ALL: List<ConfidenceSignal> = listOf(
			InterfaceKnown, LastActionResulted, ExpectedEntitiesVisible, PositionReasonable,
			HpNormal, InventoryDeltaExpected, NoUnexpectedDialog, RecentChatNormal
		)
	}
}
