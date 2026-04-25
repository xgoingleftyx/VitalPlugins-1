package net.vital.plugins.buildcore.core.services

/**
 * Runtime profile-level operational disables consulted by services at call time.
 * Distinct from Plan 2's task-edit-time [net.vital.plugins.buildcore.core.task.Effect]
 * taxonomy. Plan 5a spec §4.2.
 */
enum class OperationalRestriction
{
	GRAND_EXCHANGE_DISABLED,
	TRADING_DISABLED,
	WILDERNESS_DISABLED,
	BANK_DISABLED,
	LOGIN_DISABLED,
	WORLD_HOP_DISABLED
}
